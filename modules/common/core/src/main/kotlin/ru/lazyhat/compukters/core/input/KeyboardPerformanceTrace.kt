/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.input

import jdk.jfr.Category
import jdk.jfr.Enabled
import jdk.jfr.Event
import jdk.jfr.EventType
import jdk.jfr.FlightRecorder
import jdk.jfr.Label
import jdk.jfr.Name
import jdk.jfr.StackTrace

/** Opt-in observations only; capture and sequence are the existing transport identities. */
object KeyboardPerformanceTrace {
    const val EVENT_NAME = "ru.lazyhat.compukters.KeyboardPhase"
    private val type by lazy { EventType.getEventType(KeyboardPhaseEvent::class.java) }

    fun begin(phase: String): KeyboardPhaseEvent? {
        if (!FlightRecorder.isInitialized() || !type.isEnabled) return null
        return KeyboardPhaseEvent().also {
            it.phase = phase
            it.begin()
        }
    }
}

@Name(KeyboardPerformanceTrace.EVENT_NAME)
@Label("Keyboard pipeline phase")
@Category("Compukters", "Input")
@Enabled(false)
@StackTrace(false)
class KeyboardPhaseEvent : Event() {
    @JvmField var phase: String = ""

    @JvmField var capture: String = ""

    @JvmField var sequence: Long = -1

    @JvmField var keys: Long = 0

    @JvmField var pressed: Long = 0

    @JvmField var released: Long = 0

    @JvmField var tick: Long = -1

    @JvmField var key: Int = -1

    @JvmField var action: Int = -1

    @JvmField var release: Boolean = false

    @JvmField var connected: Boolean = false
}
