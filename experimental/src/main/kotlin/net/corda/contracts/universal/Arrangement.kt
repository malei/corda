package net.corda.contracts.universal

import net.corda.core.contracts.Frequency
import net.corda.core.crypto.Party
import java.math.BigDecimal
import java.time.LocalDate
import java.util.*

interface Arrangement

// A base arrangement with no rights and no obligations. Contract cancellation/termination is a transition to ``Zero``.
class Zero : Arrangement {
    override fun hashCode(): Int {
        return 0
    }

    override fun equals(other: Any?): Boolean {
        return other is Zero
    }
}

// A basic arrangement representing immediate transfer of Cash - X amount of currency CCY from party A to party B.
// X is an observable of type BigDecimal.
//
// TODO: should be replaced with something that uses Corda assets and/or cash?
// TODO: should enforce only transferring non-negative amounts
data class Obligation(val currency: Currency, val from: Party, val to: Party, val amount: BigDecimal = BigDecimal.ONE) : Arrangement {
    override fun equals(other: Any?): Boolean =
            when (other) {
                is Obligation -> currency.equals(other.currency) && from.equals(other.from) && to.equals(other.to)
                && amount.compareTo(other.amount) == 0
                      //  && amount.equals(other.amount)
                else -> false
            }

    override fun hashCode(): Int {
        return 0
    }

}

// A combinator over a list of arrangements. Each arrangement in list will create a separate independent arrangement state.
// The ``And`` combinator cannot be root in a arrangement.
data class And(val arrangements: Set<Arrangement>) : Arrangement

data class Action(val name: String, val condition: Perceivable<Boolean>, val arrangement: Arrangement)

// An action combinator. This declares a list of named action that can be taken by anyone of the actors given that
// _condition_ is met. If the action is performed the arrangement state transitions into the specified arrangement.
data class Actions(val actions: Set<Action>) : Arrangement

data class Scale(val amount: Perceivable<BigDecimal>, val arrangement: Arrangement) : Arrangement

// Roll out of arrangement
// TODO: fixing offset
// TODO: think about payment offset (ie. settlement) - probably it doesn't belong on a distributed ledger
data class RollOut(val startDate: LocalDate, val endDate: LocalDate, val frequency: Frequency, val template: Arrangement) : Arrangement

// Continuation of roll out
// May only be used inside template for RollOut
class Continuation : Arrangement {
    override fun hashCode(): Int {
        return 1
    }

    override fun equals(other: Any?): Boolean {
        return other is Continuation
    }
}