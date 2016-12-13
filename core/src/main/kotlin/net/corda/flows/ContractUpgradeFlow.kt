package net.corda.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.*
import net.corda.core.crypto.CompositeKey
import net.corda.core.crypto.Party
import net.corda.core.flows.FlowLogic
import net.corda.core.node.CordaPluginRegistry
import net.corda.core.node.PluginServiceHub
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.UntrustworthyData
import net.corda.flows.AbstractStateReplacementFlow.*

/**
 * A flow to be used for upgrading state objects of an old contract to a new contract.
 *
 * The [Instigator] assembles the transaction for contract replacement and sends out change proposals to all participants
 * ([Acceptor]) of that state. If participants agree to the proposed change, they each sign the transaction.
 * Finally, [Instigator] sends the transaction containing all signatures back to each participant so they can record it and
 * use the new updated state for future transactions.
 */
object ContractUpgrade {

    val TOPIC = "platform.contract_upgrade"

    class Plugin : CordaPluginRegistry() {
        override val servicePlugins: List<Class<*>> = listOf(Service::class.java)
    }

    class Service(services: PluginServiceHub) : SingletonSerializeAsToken() {
        init {
            services.registerFlowInitiator(ContractUpgradeInstigator::class) { ContractUpgradeAcceptor(it) }
        }
    }

    data class ContractUpgradeProposal<in S : ContractState>(override val stateRef: StateRef,
                                                             override val modification: UpgradedContract<S>,
                                                             override val stx: SignedTransaction) : Proposal<UpgradedContract<S>>

    internal fun <T : ContractState> assembleBareTx(stateRef: StateAndRef<T>, newContract: UpgradedContract<T>) =
            TransactionType.General.Builder(stateRef.state.notary).withItems(stateRef, newContract.upgrade(stateRef.state.data))

    class ContractUpgradeInstigator<S : ContractState>(originalState: StateAndRef<S>,
                                                       newContract: UpgradedContract<S>,
                                                       progressTracker: ProgressTracker = tracker()) : Instigator<S, UpgradedContract<S>>(originalState, newContract, progressTracker) {

        override fun assembleProposal(stateRef: StateRef, modification: UpgradedContract<S>, stx: SignedTransaction) = ContractUpgradeProposal(stateRef, modification, stx)

        override fun assembleTx(): Pair<SignedTransaction, List<CompositeKey>> {
            val ptx = assembleBareTx(originalState, modification)
            ptx.signWith(serviceHub.legalIdentityKey)
            return Pair(ptx.toSignedTransaction(false), originalState.state.data.participants)
        }
    }

    class ContractUpgradeAcceptor(otherSide: Party, override val progressTracker: ProgressTracker = tracker()) : Acceptor<UpgradedContract<ContractState>>(otherSide) {
        @Suspendable
        override fun verifyProposal(maybeProposal: UntrustworthyData<Proposal<UpgradedContract<ContractState>>>) = maybeProposal.unwrap { proposal ->
            val states = serviceHub.vaultService.statesForRefs(listOf(proposal.stateRef))
            val state = states[proposal.stateRef] ?: throw IllegalStateException("We don't have a copy of the referenced state")

            val stateAndRef = StateAndRef(state, proposal.stateRef)
            val acceptedModification = serviceHub.vaultService.getUpgradeCandidates(state.data.contract)

            val actualTx = proposal.stx.tx
            val expectedTx = assembleBareTx(stateAndRef, proposal.modification).toWireTransaction()

            requireThat {
                "the proposed contract $proposal.contract is a trusted upgrade path" by (proposal.modification == acceptedModification)
                "the proposed tx matches the expected tx for this upgrade" by (actualTx == expectedTx)
            }
            proposal
        }
    }

    class ContractUpgradeFlow<T : ContractState>(val oldContract: List<StateAndRef<T>>, val toNewContract: (List<StateAndRef<T>>) -> UpgradedContract<T>) : FlowLogic<Unit>() {
        override fun call() {
            serviceHub.vaultService.upgradeContracts(oldContract, toNewContract(oldContract))
        }
    }

}