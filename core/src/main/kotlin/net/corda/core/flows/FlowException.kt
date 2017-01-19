package net.corda.core.flows

/**
 * Exception which can be thrown by a [FlowLogic] at anypoint in its logic to unexpectedly bring it to a permanent end.
 * The exception will propagate to all counterparty flows and will be thrown on their end the next time they wait on a
 * [FlowLogic.receive] or [FlowLogic.sendAndReceive]. Any flow which no longer needs to do a receive, or has already ended,
 * will not receive the exception.
 *
 * [FlowException] (or a subclass) can be a valid expected response from a flow, particularly ones which act as a service.
 * It is recommended a [FlowLogic] document the [FlowException] types it can throw.
 */
// TODO Should we consider the case where a flow throws near the end and there's no upcoming send and the other side has
// already finished and so misses the exception? If we want to fix this then we need to have a flow wait at the end of its
// execution for all its counterparties to end as well (by waiting for the session end messages).
open class FlowException @JvmOverloads constructor(
        message: String? = null,
        cause: Throwable? = null) : Exception(message, cause)
