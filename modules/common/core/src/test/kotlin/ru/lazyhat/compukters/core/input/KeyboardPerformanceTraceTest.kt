/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.input

import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class KeyboardPerformanceTraceTest {
    @Test
    fun `recording preserves transport identity and coalesced snapshot masks`() {
        val file = Files.createTempFile("keyboard-phases", ".jfr")
        try {
            Recording().use { recording ->
                recording.enable(KeyboardPerformanceTrace.EVENT_NAME).withoutStackTrace()
                recording.start()
                val state = KeyboardInputState(3)
                state.connect(10)
                state.update(7, 3, 10)
                state.update(8, 1, 11)
                val frame = state.poll(11)
                assertNotNull(KeyboardPerformanceTrace.begin("keyboard.snapshot")).apply {
                    capture = "test-capture"
                    sequence = 8
                    keys = frame.held
                    pressed = frame.pressed
                    released = frame.released
                    tick = frame.tick
                    connected = frame.connected
                    commit()
                }
                recording.stop()
                recording.dump(file)
                val event = RecordingFile.readAllEvents(file).single { it.eventType.name == KeyboardPerformanceTrace.EVENT_NAME }
                assertEquals("keyboard.snapshot", event.getString("phase"))
                assertEquals("test-capture", event.getString("capture"))
                assertEquals(8L, event.getLong("sequence"))
                assertEquals(1L, event.getLong("keys"))
                assertEquals(3L, event.getLong("pressed"))
                assertEquals(2L, event.getLong("released"))
                assertEquals(11L, event.getLong("tick"))
                assertEquals(true, event.getBoolean("connected"))
            }
        } finally {
            Files.deleteIfExists(file)
        }
        assertNull(KeyboardPerformanceTrace.begin("disabled"))
    }
}
