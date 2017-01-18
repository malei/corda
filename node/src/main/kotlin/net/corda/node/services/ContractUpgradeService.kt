package net.corda.node.services

import net.corda.core.node.CordaPluginRegistry
import net.corda.core.node.PluginServiceHub
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.flows.ContractUpgradeReplacementFlow
import java.util.function.Function

object ContractUpgrade {
    class Plugin : CordaPluginRegistry() {
        override val servicePlugins = listOf(Function(::Service))
    }

    class Service(services: PluginServiceHub) : SingletonSerializeAsToken() {
        init {
            services.registerFlowInitiator(ContractUpgradeReplacementFlow.InstigatorFlow::class) { ContractUpgradeReplacementFlow.AcceptorFlow(it) }
        }
    }
}
