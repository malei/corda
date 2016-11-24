package net.corda.core.crypto

import net.corda.core.messaging.CordaRPCOps
import net.corda.core.node.services.IdentityService

/**
 * Reference to a [Party] which is safe to use in contract states. Its use indicates that the key is intended to be used
 * to identify a party, as opposed to a stand alone key.
 */
class StateParty(val owningKey: CompositeKey, @Transient var party: Party? = null) {
    val name: String? get() = party?.name

    override fun equals(other: Any?): Boolean {
        return if (other is StateParty) {
            owningKey == other.owningKey
        } else {
            false
        }

    }

    override fun hashCode(): Int = owningKey.hashCode()
    override fun toString(): String = owningKey.toString()

    fun resolveParty(rpc: CordaRPCOps): Party? {
        if (party == null) {
            party = rpc.partyFromKey(owningKey)
        }
        return party
    }

    fun resolveParty(service: IdentityService): Party? {
        if (party == null) {
            party = service.partyFromKey(owningKey)
        }
        return party
    }
}