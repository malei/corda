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
object ContractUpgrade {
    data class Proposal<in S : ContractState, out T : ContractState, out U : UpgradedContract<S, T>>(override val stateRef: StateRef,
                                                                                                     override val modification: U,
                                                                                                     override val stx: SignedTransaction) : AbstractStateReplacementFlow.Proposal<U>

    private fun <S : ContractState, T : ContractState, U : UpgradedContract<S, T>> assembleBareTx(stateRef: StateAndRef<S>, newContract: U) =
            TransactionType.General.Builder(stateRef.state.notary).apply {
                val (upgradedContract, command) = newContract.upgrade(stateRef.state.data)
                withItems(stateRef, upgradedContract, Command(command, stateRef.state.data.participants))
            }

    class InstigatorFlow<S : ContractState, out T : ContractState, U : UpgradedContract<S, T>>(originalState: StateAndRef<S>,
                                                                                               newContract: U)
        : Instigator<S, T, U>(originalState, newContract) {

        override fun assembleProposal(stateRef: StateRef, modification: U, stx: SignedTransaction) = Proposal(stateRef, modification, stx)

        override fun assembleTx() = assembleBareTx(originalState, modification).let {
            it.signWith(serviceHub.legalIdentityKey)
            Pair(it.toSignedTransaction(false), originalState.state.data.participants)
        }
    }

    class AcceptorFlow(otherSide: Party) : Acceptor<UpgradedContract<ContractState, ContractState>>(otherSide) {
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

class ContractUpgradeAcceptFlow<S : ContractState, out T : ContractState, out U : UpgradedContract<S, T>>(val ref: StateAndRef<S>, val newContract: U) : FlowLogic<Unit>() {
    override fun call() {
        serviceHub.vaultService.acceptContractUpgrade(ref, newContract)
    }
}

class ContractUpgradeFlow<S : ContractState, out T : ContractState, out U : UpgradedContract<S, T>>(val ref: StateAndRef<S>,
                                                                                                    val newContract: U) : FlowLogic<ContractUpgradeResponse>() {
    @Suspendable
    override fun call(): ContractUpgradeResponse = try {
        ContractUpgradeResponse.Accepted(subFlow(ContractUpgrade.InstigatorFlow(ref, newContract)))
    } catch (e: Exception) {
        ContractUpgradeResponse.Rejected(e.message)
    }
}
