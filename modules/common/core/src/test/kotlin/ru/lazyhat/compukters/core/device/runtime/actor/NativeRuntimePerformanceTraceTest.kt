/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleet
import ru.lazyhat.compukters.lang.runtime.vm.VmFleetBusyException
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity

class NativeRuntimePerformanceTraceTest {
    @Test
    fun `trace distinguishes admission from accepted delivery and preserves expired grant`() {
        val file = Files.createTempFile("native-runtime-phases", ".jfr")
        var busy = true
        val fleet = Proxy.newProxyInstance(
            ExperimentalFleet::class.java.classLoader,
            arrayOf(ExperimentalFleet::class.java),
        ) { _, method, _ ->
            check(method.name == "grant" || method.name == "reply")
            if (busy) throw VmFleetBusyException("busy")
            null
        } as ExperimentalFleet
        try {
            Recording().use { recording ->
                recording.enable(NativeRuntimePerformanceTrace.EVENT_NAME).withoutStackTrace()
                recording.start()
                val outbox = NativeFleetOutbox(fleet, 8, 8, {}, nanoTime = { 10L })
                assertTrue(outbox.offer(7, NativeFleetOutbox.Command.Grant(11, 128, 5)))
                assertEquals(0, outbox.flush(1))
                busy = false
                assertEquals(1, outbox.flush(1))
                assertTrue(outbox.offer(7, NativeFleetOutbox.Command.Reply(VmHostRequestIdentity(3, 42), HostResponse.UnitSuccess)))
                assertEquals(1, outbox.flush(1))
                recording.stop()
                recording.dump(file)
                val events = RecordingFile.readAllEvents(file).filter { it.eventType.name == NativeRuntimePerformanceTrace.EVENT_NAME }
                assertEquals(listOf("queued", "sent", "queued", "sent"), events.map { it.getString("phase") })
                assertTrue(events.all { it.getLong("machine") == 7L })
                assertEquals(11L, events[1].getLong("tick"))
                assertEquals(128L, events[1].getLong("units"))
                assertEquals(-5L, events[1].getLong("deadlineRemainingNanos"))
                assertEquals("reply", events[3].getString("kind"))
                assertEquals(3, events[3].getInt("task"))
                assertEquals(42L, events[3].getLong("request"))
            }
        } finally {
            Files.deleteIfExists(file)
        }
        assertNull(NativeRuntimePerformanceTrace.begin("disabled", 7))
        NativeRuntimePerformanceTrace.command("disabled", 7, NativeFleetOutbox.Command.Grant(1, 128, 5)) {
            error("Disabled tracing must not read the clock")
        }
    }
}
