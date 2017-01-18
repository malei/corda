package net.corda.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.*
import net.corda.core.crypto.Party
import net.corda.core.flows.FlowLogic
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.UntrustworthyData
import net.corda.flows.AbstractStateReplacementFlow.Acceptor
import net.corda.flows.AbstractStateReplacementFlow.Instigator

/**
 * A flow to be used for upgrading state objects of an old contract to a new contract.
 *
 * The [Instigator] assembles the transaction for contract replacement and sends out change proposals to all participants
 * ([Acceptor]) of that state. If participants agree to the proposed change, they each sign the transaction.
 * Finally, [Instigator] sends the transaction containing all signatures back to each participant so they can record it and
 * use the new updated state for future transactions.
 */
object ContractUpgradeReplacementFlow {
    data class Proposal<in S : ContractState, out T : ContractState, out U : ContractUpgrade<S, T>>(override val stateRef: StateRef,
                                                                                                    override val modification: U,
                                                                                                    override val stx: SignedTransaction) : AbstractStateReplacementFlow.Proposal<U>

    private fun <S : ContractState, T : ContractState, U : ContractUpgrade<S, T>> assembleBareTx(stateRef: StateAndRef<S>, contractUpgrade: U) =
            TransactionType.General.Builder(stateRef.state.notary).apply {
                val (upgradedState, command) = contractUpgrade.upgrade(stateRef.state.data)
                withItems(stateRef, upgradedState, Command(command, stateRef.state.data.participants))
            }

    class InstigatorFlow<S : ContractState, out T : ContractState, U : ContractUpgrade<S, T>>(originalState: StateAndRef<S>,
                                                                                              newContract: U)
        : Instigator<S, T, U>(originalState, newContract) {

        override fun assembleProposal(stateRef: StateRef, modification: U, stx: SignedTransaction) = Proposal(stateRef, modification, stx)

        override fun assembleTx() = assembleBareTx(originalState, modification).let {
            it.signWith(serviceHub.legalIdentityKey)
            Pair(it.toSignedTransaction(false), originalState.state.data.participants)
        }
    }

    class AcceptorFlow(otherSide: Party) : Acceptor<ContractUpgrade<ContractState, ContractState>>(otherSide) {
        @Suspendable
        override fun verifyProposal(maybeProposal: UntrustworthyData<AbstractStateReplacementFlow.Proposal<ContractUpgrade<ContractState, ContractState>>>) = maybeProposal.unwrap { proposal ->
            val tx = serviceHub.storageService.validatedTransactions.getTransaction(proposal.stateRef.txhash) ?: throw IllegalStateException("We don't have a copy of the referenced state")
            val state = tx.tx.outRef<ContractState>(proposal.stateRef.index)
            val acceptedUpgrade = serviceHub.vaultService.getAcceptedContractStateUpgrade(state) ?: throw IllegalStateException("Contract state upgrade rejected. State hash : ${state.ref}")
            val actualTx = proposal.stx.tx
            actualTx.inputs.map { serviceHub.vaultService.statesForRef(it) }
            val expectedTx = assembleBareTx(state, proposal.modification).toWireTransaction()
            requireThat {
                "the proposed contract ${proposal.modification} is a trusted upgrade path" by (proposal.modification.javaClass == acceptedUpgrade.javaClass)
                "the proposed tx matches the expected tx for this upgrade" by (actualTx == expectedTx)
            }
            proposal
        }
    }
}

class ContractUpgradeAcceptFlow<S : ContractState, out T : ContractState, out U : ContractUpgrade<S, T>>(val state: StateAndRef<S>, val contractUpgrade: U) : FlowLogic<Unit>() {
    override fun call() {
        serviceHub.vaultService.acceptContractStateUpgrade(state, contractUpgrade)
    }
}

class ContractUpgradeFlow<S : ContractState, out T : ContractState, out U : ContractUpgrade<S, T>>(val state: StateAndRef<S>,
                                                                                                   val newContract: U) : FlowLogic<ContractUpgradeResponse>() {
    @Suspendable
    override fun call() = try {
        ContractUpgradeResponse.Accepted(subFlow(ContractUpgradeReplacementFlow.InstigatorFlow(state, newContract)))
    } catch (e: Exception) {
        ContractUpgradeResponse.Rejected(e.message)
    }
}
