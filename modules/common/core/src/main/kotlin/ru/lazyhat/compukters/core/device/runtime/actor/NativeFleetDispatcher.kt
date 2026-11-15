/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleet
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetEvent
import java.util.concurrent.CompletableFuture

/**
 * Owner-thread entry point for native observations and ordered command retries. Lifecycle
 * output is deliberately separate: it can overtake already-published ordinary observations.
 * Its consumer reconciles canceled/preserved work, then invalidates the old machine identity
 * before later ordinary observations can trigger world effects. No closed-identity history
 * is retained here; the owner routes observations through its live endpoint registry.
 */
internal class NativeFleetDispatcher(
    fleet: ExperimentalFleet,
    maximumCommands: Int,
    maximumCommandsPerMachine: Int,
    private val checkOwner: () -> Unit,
    onResultsReady: () -> Unit,
    deliver: (ExperimentalFleetEvent) -> Unit,
    byteLimits: NativeFleetOutboxLimits = NativeFleetOutboxLimits(),
) : AutoCloseable {
    private var closed = false
    val outbox = NativeFleetOutbox(fleet, maximumCommands, maximumCommandsPerMachine, checkOwner, byteLimits = byteLimits)
    private val events =
        NativeFleetEventPump(fleet, checkOwner, onResultsReady, deliver)

    /** Called for notifications and periodically on ticks, even if no events were published. */
    fun pump(
        maximumEvents: Int,
        maximumCommandAttempts: Int,
        deadlineNanos: Long = Long.MAX_VALUE,
    ): Int {
        require(maximumCommandAttempts >= 0)
        val delivered = events.pump(maximumEvents, deadlineNanos)
        outbox.flush(maximumCommandAttempts, deadlineNanos)
        return delivered
    }

    /**
     * Independent, bounded close progress, including when ordinary delivery is suspended.
     * Run on owner notifications and ticks, and while awaiting asynchronous shutdown. Prefixes
     * must be reconciled by the lifecycle owner rather than dispatched as new world requests.
     */
    fun pumpLifecycle(
        maximumEvents: Int,
        maximumAttempts: Int,
        deadlineNanos: Long = Long.MAX_VALUE,
        deliver: (ExperimentalFleetEvent) -> Unit,
    ): Int {
        checkOwner()
        check(!closed) { "native fleet dispatcher is closed" }
        return outbox.pumpLifecycle(maximumEvents, maximumAttempts, deadlineNanos, deliver)
    }

    /** Stop pumping only after all machine lifecycle barriers have completed. */
    fun closeAsync(): CompletableFuture<Unit> {
        val completion = events.closeAsync()
        closed = true
        return completion
    }

    override fun close() {
        closeAsync().join()
    }
}
