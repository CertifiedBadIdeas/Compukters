/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.program.integration

import ru.lazyhat.compukters.core.device.runtime.actor.NativeFleetEventPump
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetConfig
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetEvent
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime
import ru.lazyhat.compukters.lang.runtime.vm.VmSession
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class NativeFleetEventPumpIntegrationTest {
    @Test
    fun `native events wake the owner and are delivered on the owner thread`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.terminal.fixture"))).readBytes()
        val owner = Thread.currentThread()
        val notifications = LinkedBlockingQueue<Unit>()
        val events = mutableListOf<ExperimentalFleetEvent>()
        VmRuntime.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 32, 128, 64, 64, 32)).use { fleet ->
            val session = VmSession.open(artifact)
            val machine = session.transferToFleet(fleet)
            NativeFleetEventPump(fleet, { check(Thread.currentThread() === owner) }, { notifications.offer(Unit) }, events::add).use { pump ->
                fun await(predicate: (ExperimentalFleetEvent) -> Boolean) {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (events.none(predicate)) {
                        pump.pump(32)
                        if (events.any(predicate)) break
                        val remaining = deadline - System.nanoTime()
                        check(remaining > 0) { "timed out awaiting fleet event" }
                        assertNotNull(notifications.poll(remaining, TimeUnit.NANOSECONDS))
                    }
                }
                fleet.resourceSnapshot(machine, 42)
                await { it is ExperimentalFleetEvent.Resources && it.request == 42L }
                assertEquals(1, events.count { it is ExperimentalFleetEvent.Resources && it.request == 42L })
                fleet.stop(machine)
                await { it is ExperimentalFleetEvent.Stopped && it.machine == machine }
            }
            session.close()
        }
    }
}
