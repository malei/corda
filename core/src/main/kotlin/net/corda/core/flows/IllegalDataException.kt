package net.corda.core.flows

/**
 * A [FlowException] informing the sending [FlowLogic] of an invalid piece of data it has sent.
 */
open class IllegalDataException @JvmOverloads constructor(
        message : String? = null,
        cause : Throwable? = null) : FlowException(message, cause)
