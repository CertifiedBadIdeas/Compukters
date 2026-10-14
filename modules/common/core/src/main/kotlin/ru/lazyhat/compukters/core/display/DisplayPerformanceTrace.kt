/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.display

import jdk.jfr.Category
import jdk.jfr.Enabled
import jdk.jfr.Event
import jdk.jfr.EventType
import jdk.jfr.FlightRecorder
import jdk.jfr.Label
import jdk.jfr.Name
import jdk.jfr.StackTrace

/** Explicitly enabled JFR observation; never starts a recording or retains an event history. */
object DisplayPerformanceTrace {
    const val EVENT_NAME = "ru.lazyhat.compukters.DisplayPhase"
    private val type by lazy { EventType.getEventType(DisplayPhaseEvent::class.java) }

    fun begin(phase: String): DisplayPhaseEvent? {
        if (!FlightRecorder.isInitialized() || !type.isEnabled) return null
        return DisplayPhaseEvent().also {
            it.phase = phase
            it.begin()
        }
    }

    fun pointer(
        phase: String,
        canvas: DisplayCanvas,
        kind: Int,
        gesture: Int,
        x: Int,
        y: Int,
    ) {
        begin(phase)?.apply {
            this.canvas = System.identityHashCode(canvas)
            this.kind = kind
            this.gesture = gesture
            this.x = x
            this.y = y
            commit()
        }
    }
}

@Name(DisplayPerformanceTrace.EVENT_NAME)
@Label("Display pipeline phase")
@Category("Compukters", "Display")
@Enabled(false)
@StackTrace(false)
class DisplayPhaseEvent : Event() {
    @JvmField var phase: String = ""

    @JvmField var canvas: Int = 0

    @JvmField var surface: String = ""

    @JvmField var revision: Long = 0

    @JvmField var publication: Long = 0

    @JvmField var panel: Long = 0

    @JvmField var gesture: Int = 0

    @JvmField var kind: Int = 0

    @JvmField var x: Int = 0

    @JvmField var y: Int = 0

    @JvmField var bytes: Int = 0

    @JvmField var panels: Int = 0

    @JvmField var held: Boolean = false
}
