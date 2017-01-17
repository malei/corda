package net.corda.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.*
import net.corda.core.crypto.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.UntrustworthyData
import net.corda.flows.ContractUpgradeFlow.Acceptor
import net.corda.flows.ContractUpgradeFlow.Instigator

/**
 * A flow to be used for upgrading state objects of an old contract to a new contract.
 *
 * The [Instigator] assembles the transaction for contract replacement and sends out change proposals to all participants
 * ([Acceptor]) of that state. If participants agree to the proposed change, they each sign the transaction.
 * Finally, [Instigator] sends the transaction containing all signatures back to each participant so they can record it and
 * use the new updated state for future transactions.
 */
object ContractUpgradeFlow {
    data class Proposal<in S : ContractState, out T : ContractState>(override val stateRef: StateRef,
                                                                     override val modification: UpgradedContract<S, T>,
                                                                     override val stx: SignedTransaction) : AbstractStateReplacementFlow.Proposal<UpgradedContract<S, T>>

    private fun <S : ContractState, T : ContractState> assembleBareTx(stateRef: StateAndRef<S>, newContract: UpgradedContract<S, T>) = TransactionType.General.Builder(stateRef.state.notary).apply {
        addInputState(stateRef)
        val (upgradedContract, command) = newContract.upgrade(stateRef.state.data)
        addOutputState(upgradedContract)
        addCommand(command, stateRef.state.data.participants)
    }

    class Instigator<S : ContractState, T : ContractState>(originalState: StateAndRef<S>,
                                                           newContract: UpgradedContract<S, T>,
                                                           progressTracker: ProgressTracker = tracker())
        : AbstractStateReplacementFlow.Instigator<S, T, UpgradedContract<S, T>>(originalState, newContract, progressTracker) {

        override fun assembleProposal(stateRef: StateRef, modification: UpgradedContract<S, T>, stx: SignedTransaction) = Proposal(stateRef, modification, stx)

        override fun assembleTx() = assembleBareTx(originalState, modification).let {
            it.signWith(serviceHub.legalIdentityKey)
            Pair(it.toSignedTransaction(false), originalState.state.data.participants)
        }
    }

    class Acceptor(otherSide: Party, override val progressTracker: ProgressTracker = tracker()) : AbstractStateReplacementFlow.Acceptor<UpgradedContract<ContractState, ContractState>>(otherSide) {
        @Suspendable
        override fun verifyProposal(maybeProposal: UntrustworthyData<AbstractStateReplacementFlow.Proposal<UpgradedContract<ContractState, ContractState>>>) = maybeProposal.unwrap { proposal ->
            val tx = serviceHub.storageService.validatedTransactions.getTransaction(proposal.stateRef.txhash) ?: throw IllegalStateException("We don't have a copy of the referenced state")
            val state = tx.tx.outRef<ContractState>(proposal.stateRef.index).state
            val acceptedUpgrade = serviceHub.vaultService.getUpgradeableContract(state.data.contract) ?: throw IllegalStateException("Contract ${state.data.contract}, upgrade rejected.")
            val actualTx = proposal.stx.tx
            actualTx.inputs.map { serviceHub.vaultService.statesForRef(it) }
            val expectedTx = assembleBareTx(StateAndRef(state, proposal.stateRef), proposal.modification).toWireTransaction()
            requireThat {
                "the proposed contract ${proposal.modification} is a trusted upgrade path" by (proposal.modification.javaClass == acceptedUpgrade.javaClass)
                "the proposed tx matches the expected tx for this upgrade" by (actualTx == expectedTx)
            }
            proposal
        }
    }
}