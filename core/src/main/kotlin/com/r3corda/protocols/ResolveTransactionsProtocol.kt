package com.r3corda.protocols

import co.paralleluniverse.fibers.Suspendable
import com.r3corda.core.checkedAdd
import com.r3corda.core.crypto.Party
import com.r3corda.core.crypto.SecureHash
import com.r3corda.core.protocols.ProtocolLogic
import com.r3corda.core.transactions.LedgerTransaction
import com.r3corda.core.transactions.SignedTransaction
import com.r3corda.core.transactions.WireTransaction
import java.util.*

// TODO: This code is currently unit tested by TwoPartyTradeProtocolTests, it should have its own tests.

// TODO: It may be a clearer API if we make the primary c'tor private here, and only allow a single tx to be "resolved".

/**
 * This protocol is used to verify the validity of a transaction by recursively checking the validity of all the
 * dependencies. Once a transaction is checked it's inserted into local storage so it can be relayed and won't be
 * checked again.
 *
 * A couple of constructors are provided that accept a single transaction. When these are used, the dependencies of that
 * transaction are resolved and then the transaction itself is verified. Again, if successful, the results are inserted
 * into the database as long as a [SignedTransaction] was provided. If only the [WireTransaction] form was provided
 * then this isn't enough to put into the local database, so only the dependencies are checked and inserted. This way
 * to use the protocol is helpful when resolving and verifying a finished but partially signed transaction.
 *
 * The protocol returns a list of verified [LedgerTransaction] objects, in a depth-first order.
 */
class ResolveTransactionsProtocol(private val txHashes: Set<SecureHash>,
                                  private val otherSide: Party) : ProtocolLogic<List<LedgerTransaction>>() {

    companion object {
        private fun dependencyIDs(wtx: WireTransaction) = wtx.inputs.map { it.txhash }.toSet()
    }

    class ExcessivelyLargeTransactionGraph() : Exception()

    // Transactions to verify after the dependencies.
    private var stx: SignedTransaction? = null
    private var wtx: WireTransaction? = null

    // TODO: Figure out a more appropriate DOS limit here, 5000 is simply a very bad guess.
    /** The maximum number of transactions this protocol will try to download before bailing out. */
    var transactionCountLimit = 5000

    /**
     * Resolve the full history of a transaction and verify it with its dependencies.
     */
    constructor(stx: SignedTransaction, otherSide: Party) : this(stx.tx, otherSide) {
        this.stx = stx
    }

    /**
     * Resolve the full history of a transaction and verify it with its dependencies.
     */
    constructor(wtx: WireTransaction, otherSide: Party) : this(dependencyIDs(wtx), otherSide) {
        this.wtx = wtx
    }

    @Suspendable
    override fun call(): List<LedgerTransaction> {
        val newTxns: Iterable<SignedTransaction> = downloadDependencies(txHashes)

        // For each transaction, verify it and insert it into the database. As we are iterating over them in a
        // depth-first order, we should not encounter any verification failures due to missing data. If we fail
        // half way through, it's no big deal, although it might result in us attempting to re-download data
        // redundantly next time we attempt verification.
        val result = ArrayList<LedgerTransaction>()

        for (stx in newTxns) {
            // Resolve to a LedgerTransaction and then run all contracts.
            val ltx = stx.toLedgerTransaction(serviceHub)
            ltx.verify()
            serviceHub.recordTransactions(stx)
            result += ltx
        }

        // If this protocol is resolving a specific transaction, make sure we have its attachments and then verify
        // it as well, but don't insert to the database. Note that when we were given a SignedTransaction (stx != null)
        // we *could* insert, because successful verification implies we have everything we need here, and it might
        // be a clearer API if we do that. But for consistency with the other c'tor we currently do not.
        //
        // If 'stx' is set, then 'wtx' is the contents (from the c'tor).
        val wtx = stx?.verifySignatures() ?: wtx
        wtx?.let {
            fetchMissingAttachments(listOf(it))
            val ltx = it.toLedgerTransaction(serviceHub)
            ltx.verify()
            result += ltx
        }

        return result
    }

    override val topic: String get() = throw UnsupportedOperationException()

    @Suspendable
    private fun downloadDependencies(depsToCheck: Set<SecureHash>): List<SignedTransaction> {
        // Maintain a work queue of all hashes to load/download, initialised with our starting set. Then do a breadth
        // first traversal across the dependency graph.
        //
        // TODO: This approach has two problems. Analyze and resolve them:
        //
        // (1) This protocol leaks private data. If you download a transaction and then do NOT request a
        // dependency, it means you already have it, which in turn means you must have been involved with it before
        // somehow, either in the tx itself or in any following spend of it. If there were no following spends, then
        // your peer knows for sure that you were involved ... this is bad! The only obvious ways to fix this are
        // something like onion routing of requests, secure hardware, or both.
        //
        // (2) If the identity service changes the assumed identity of one of the public keys, it's possible
        // that the "tx in db is valid" invariant is violated if one of the contracts checks the identity! Should
        // the db contain the identities that were resolved when the transaction was first checked, or should we
        // accept this kind of change is possible? Most likely solution is for identity data to be an attachment.

        val nextRequests = LinkedHashSet<SecureHash>()   // Keep things unique but ordered, for unit test stability.
        nextRequests.addAll(depsToCheck)
        val resultQ = LinkedHashMap<SecureHash, SignedTransaction>()

        val limit = transactionCountLimit
        check(limit > 0) { "$limit is not a valid count limit" }
        var limitCounter = 0
        while (nextRequests.isNotEmpty()) {
            // Don't re-download the same tx when we haven't verified it yet but it's referenced multiple times in the
            // graph we're traversing.
            val notAlreadyFetched = nextRequests.filterNot { it in resultQ }.toSet()
            nextRequests.clear()

            if (notAlreadyFetched.isEmpty())   // Done early.
                break

            // Request the standalone transaction data (which may refer to things we don't yet have).
            val downloads: List<SignedTransaction> = subProtocol(FetchTransactionsProtocol(notAlreadyFetched, otherSide)).downloaded

            fetchMissingAttachments(downloads.map { it.tx })

            for (stx in downloads)
                check(resultQ.putIfAbsent(stx.id, stx) == null)   // Assert checks the filter at the start.

            // Add all input states to the work queue.
            val inputHashes = downloads.flatMap { it.tx.inputs }.map { it.txhash }
            nextRequests.addAll(inputHashes)

            limitCounter = limitCounter checkedAdd nextRequests.size
            if (limitCounter > limit)
                throw ExcessivelyLargeTransactionGraph()
        }

        return resultQ.values.reversed()
    }

    /**
     * Returns a list of all the dependencies of the given transactions, deepest first i.e. the last downloaded comes
     * first in the returned list and thus doesn't have any unverified dependencies.
     */
    @Suspendable
    private fun fetchMissingAttachments(downloads: List<WireTransaction>) {
        // TODO: This could be done in parallel with other fetches for extra speed.
        val missingAttachments = downloads.flatMap { wtx ->
            wtx.attachments.filter { serviceHub.storageService.attachments.openAttachment(it) == null }
        }
        if (missingAttachments.isNotEmpty())
            subProtocol(FetchAttachmentsProtocol(missingAttachments.toSet(), otherSide))
    }
}
