/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.display

import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DisplayPerformanceTraceTest {
    @Test
    fun `recording captures queue poll and publication with the canvas revision`() {
        val file = Files.createTempFile("display-phases", ".jfr")
        try {
            Recording().use { recording ->
                recording.enable(DisplayPerformanceTrace.EVENT_NAME).withoutStackTrace()
                recording.start()
                val canvas = DisplayCanvas(1, 1, 0)
                val owner = Any()
                canvas.acquire(owner) { true }
                canvas.offerClick(2, 3)
                assertNotNull(canvas.pollClick(owner))
                canvas.begin(owner)
                canvas.pixel(owner, 2, 3, 0x123456)
                canvas.finish(owner, true)
                recording.stop()
                recording.dump(file)
                val events = RecordingFile.readAllEvents(file).filter { it.eventType.name == DisplayPerformanceTrace.EVENT_NAME }
                assertEquals(listOf("input.queued", "input.polled", "frame.published"), events.map { it.getString("phase") })
                assertTrue(events.all { it.getInt("canvas") == System.identityHashCode(canvas) })
                assertEquals(canvas.revision, events.last().getLong("revision"))
                assertTrue(events.last().duration.toNanos() > 0)
            }
        } finally {
            Files.deleteIfExists(file)
            assertNull(DisplayPerformanceTrace.begin("disabled"))
        }
    }
}
