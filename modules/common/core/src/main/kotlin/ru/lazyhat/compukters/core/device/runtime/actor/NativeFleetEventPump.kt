/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.core.LOGGER
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleet
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Bridges native readiness to the owner thread. Only [pump] consumes events or runs [deliver];
 * the notification thread may only ask the owner to pump. The caller owns the fleet and must
 * close this pump before closing it.
 */
internal class NativeFleetEventPump(
    private val fleet: ExperimentalFleet,
    private val checkOwner: () -> Unit,
    private val onResultsReady: () -> Unit,
    private val deliver: (ExperimentalFleetEvent) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val eventNotificationPending = AtomicBoolean()
    private val failure = AtomicReference<Throwable?>()
    private val waiterStopped = CompletableFuture<Unit>()
    private val initialGeneration = fleet.eventGeneration()
    private val waiter =
        Thread
            .ofPlatform()
            .daemon(true)
            .name("compukters-native-fleet-events")
            .unstarted(::waitForEvents)

    init {
        checkOwner()
        waiter.start()
    }

    fun pump(
        maximumEvents: Int,
        deadlineNanos: Long = Long.MAX_VALUE,
    ): Int {
        checkOwner()
        require(maximumEvents >= 0)
        check(!closed.get()) { "native fleet event pump is closed" }
        failure.get()?.let { throw IllegalStateException("native fleet notification failed", it) }
        // Command-only pumping cannot acknowledge an observation it is not allowed to read.
        if (maximumEvents > 0) eventNotificationPending.set(false)
        var count = 0
        while (count < maximumEvents && (deadlineNanos == Long.MAX_VALUE || nanoTime() - deadlineNanos < 0)) {
            val event = fleet.poll() ?: break
            count++
            deliver(event)
        }
        return count
    }

    fun requestPump() {
        try {
            onResultsReady()
        } catch (error: Exception) {
            LOGGER.warn(error) { "Native VM result notification failed; periodic owner delivery remains available" }
        }
    }

    private fun requestEventPump() {
        if (eventNotificationPending.compareAndSet(false, true)) requestPump()
    }

    private fun waitForEvents() {
        try {
            var generation = initialGeneration
            // An event might have been queued before the initial generation was captured.
            if (!closed.get()) requestEventPump()
            while (!closed.get()) {
                val changed = fleet.waitEvents(generation, WAIT_MILLIS)
                if (closed.get()) break
                if (changed) {
                    generation = fleet.eventGeneration()
                    requestEventPump()
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) {
                failure.set(error)
                requestPump()
            }
        } finally {
            waiterStopped.complete(Unit)
        }
    }

    /** Complete only after the waiter has stopped using the fleet; safe to call on the owner. */
    fun closeAsync(): CompletableFuture<Unit> {
        checkOwner()
        closed.set(true)
        return waiterStopped.copy()
    }

    override fun close() {
        closeAsync().join()
    }

    private companion object {
        const val WAIT_MILLIS = 100
    }
}
