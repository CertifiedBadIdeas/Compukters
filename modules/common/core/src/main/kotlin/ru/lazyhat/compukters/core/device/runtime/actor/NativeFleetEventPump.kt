/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.core.LOGGER
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleet
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetEvent
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
    private val failure = AtomicReference<Throwable?>()
    private val initialGeneration = fleet.eventGeneration()
    private val waiter = Thread.ofPlatform().daemon(true).name("compukters-native-fleet-events").unstarted(::waitForEvents)

    init {
        checkOwner()
        waiter.start()
    }

    fun pump(maximumEvents: Int, deadlineNanos: Long = Long.MAX_VALUE): Int {
        checkOwner()
        require(maximumEvents >= 0)
        check(!closed.get()) { "native fleet event pump is closed" }
        failure.get()?.let { throw IllegalStateException("native fleet notification failed", it) }
        var count = 0
        while (count < maximumEvents && (deadlineNanos == Long.MAX_VALUE || nanoTime() - deadlineNanos < 0)) {
            val event = fleet.poll() ?: break
            count++
            deliver(event)
        }
        return count
    }

    private fun notifyOwner() {
        try {
            onResultsReady()
        } catch (error: Exception) {
            LOGGER.warn(error) { "Native VM result notification failed; periodic owner delivery remains available" }
        }
    }

    private fun waitForEvents() {
        try {
            var generation = initialGeneration
            // An event might have been queued before the initial generation was captured.
            if (!closed.get()) notifyOwner()
            while (!closed.get()) {
                val changed = fleet.waitEvents(generation, WAIT_MILLIS)
                if (closed.get()) break
                if (changed) {
                    generation = fleet.eventGeneration()
                    notifyOwner()
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) {
                failure.set(error)
                notifyOwner()
            }
        }
    }

    override fun close() {
        checkOwner()
        if (!closed.compareAndSet(false, true)) return
        if (Thread.currentThread() !== waiter) waiter.join()
    }

    private companion object {
        const val WAIT_MILLIS = 100
    }
}
