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
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    private var closed = false
    private var commandTurn = true
    private val events =
        NativeFleetEventPump(fleet, checkOwner, onResultsReady, deliver, nanoTime)
    val outbox =
        NativeFleetOutbox(
            fleet,
            maximumCommands,
            maximumCommandsPerMachine,
            checkOwner,
            nanoTime = nanoTime,
            byteLimits = byteLimits,
            onCommandsReady = events::requestPump,
        )

    /** Called for notifications and periodically on ticks, even if no events were published. */
    fun pump(
        maximumEvents: Int,
        maximumCommandAttempts: Int,
        deadlineNanos: Long = Long.MAX_VALUE,
    ): Int {
        require(maximumEvents >= 0 && maximumCommandAttempts >= 0)
        // Validate owner, lifetime and waiter failure even for command-only or expired pumps.
        events.beginPump(0)
        // Acknowledge this wakeup even when its commands were removed by a lifecycle fence.
        outbox.beginFlush()
        var delivered = 0
        var attempted = 0
        var idleLanes = 0
        while (!closed && idleLanes < 2 && (deadlineNanos == Long.MAX_VALUE || nanoTime() - deadlineNanos < 0)) {
            val send = commandTurn
            // Retain the other lane's turn if this operation exhausts the deadline or fails.
            commandTurn = !commandTurn
            if (send) {
                if (attempted < maximumCommandAttempts && outbox.hasPendingCommands) {
                    attempted++
                    outbox.sendNext()
                    idleLanes = 0
                } else {
                    idleLanes++
                }
            } else {
                val count = if (delivered < maximumEvents && events.pollNext()) 1 else 0
                delivered += count
                idleLanes = if (count > 0) 0 else idleLanes + 1
            }
        }
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
