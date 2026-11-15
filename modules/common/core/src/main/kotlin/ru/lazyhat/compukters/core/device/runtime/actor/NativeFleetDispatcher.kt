/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleet
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetEvent

/** Owner-thread entry point for native event delivery and ordered command retries. */
internal class NativeFleetDispatcher(
    fleet: ExperimentalFleet,
    maximumCommands: Int,
    maximumCommandsPerMachine: Int,
    checkOwner: () -> Unit,
    onResultsReady: () -> Unit,
    deliver: (ExperimentalFleetEvent) -> Unit,
) : AutoCloseable {
    val outbox = NativeFleetOutbox(fleet, maximumCommands, maximumCommandsPerMachine, checkOwner)
    private val events =
        NativeFleetEventPump(fleet, checkOwner, onResultsReady, { event ->
            when (event) {
                is ExperimentalFleetEvent.Stopped -> outbox.acknowledgeLifecycle(event.machine)
                is ExperimentalFleetEvent.Detached -> outbox.acknowledgeLifecycle(event.machine)
                is ExperimentalFleetEvent.DetachRejected -> outbox.acknowledgeLifecycle(event.machine)
                else -> Unit
            }
            deliver(event)
        })

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

    override fun close() = events.close()
}
