package net.corda.contracts.universal

import net.corda.core.crypto.Party
import java.math.BigDecimal
import java.util.*

fun exchange(partyA: Party, amountA: BigDecimal, currencyA: Currency, partyB: Party, amountB: BigDecimal, currencyB: Currency) =
        arrange {
            partyA.owes(partyB, amountA, currencyA)
            partyB.owes(partyA, amountB, currencyB)
        }

fun fx_swap(expiry: String, notional: BigDecimal, strike: BigDecimal,
            foreignCurrency: Currency, domesticCurrency: Currency,
            partyA: Party, partyB: Party) =
        arrange {
            actions {
                (partyA or partyB) may {
                    "execute".givenThat(after(expiry)) {
                        +exchange(partyA, notional * strike, domesticCurrency, partyB, notional, foreignCurrency)
                        // todo, the plus is required otherwise the line would have no effect
                        // need to find a way to prevent compilation
                    }
                }
            }
        }
