/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import jdk.jfr.Category
import jdk.jfr.Enabled
import jdk.jfr.Event
import jdk.jfr.EventType
import jdk.jfr.FlightRecorder
import jdk.jfr.Label
import jdk.jfr.Name
import jdk.jfr.StackTrace
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequest

/** Opt-in transport observations. Disabled recordings retain no per-machine trace state. */
internal object NativeRuntimePerformanceTrace {
    const val EVENT_NAME = "ru.lazyhat.compukters.NativeRuntimePhase"
    private val type by lazy { EventType.getEventType(NativeRuntimePhaseEvent::class.java) }

    fun begin(
        phase: String,
        machine: Long,
    ): NativeRuntimePhaseEvent? {
        if (!FlightRecorder.isInitialized() || !type.isEnabled) return null
        return NativeRuntimePhaseEvent().also {
            it.phase = phase
            it.machine = machine
            it.begin()
        }
    }

    fun command(
        phase: String,
        machine: Long,
        command: NativeFleetOutbox.Command,
        clock: () -> Long,
    ) {
        if (command !is NativeFleetOutbox.Command.FrameGrant && command !is NativeFleetOutbox.Command.Reply &&
            command !is NativeFleetOutbox.Command.ResolveCost
        ) {
            return
        }
        val event = begin(phase, machine) ?: return
        when (command) {
            is NativeFleetOutbox.Command.FrameGrant -> {
                event.kind = "grant"
                event.tick = command.tick
                event.units = command.retirements.toLong()
                event.deadlineRemainingNanos =
                    if (command.authorizedUntilNanos == Long.MAX_VALUE) Long.MAX_VALUE else command.authorizedUntilNanos - clock()
            }

            is NativeFleetOutbox.Command.Reply -> {
                event.kind = "reply"
                event.task = command.identity.taskId
                event.request = command.identity.requestId
            }

            is NativeFleetOutbox.Command.ResolveCost -> {
                event.kind = "cost"
                event.task = command.identity.taskId
                event.request = command.identity.requestId
                event.units = command.units.toLong()
            }

            else -> {
                error("unsupported trace command")
            }
        }
        event.commit()
    }

    fun request(
        phase: String,
        machine: Long,
        tick: Long,
        request: VmHostRequest,
    ) {
        begin(phase, machine)?.apply {
            this.tick = tick
            task = request.taskId
            this.request = request.id
            namespace = request.capability.namespace
            capability = request.capability.name
            operation = request.operation
            commit()
        }
    }
}

@Name(NativeRuntimePerformanceTrace.EVENT_NAME)
@Label("Native VM delivery phase")
@Category("Compukters", "Runtime")
@Enabled(false)
@StackTrace(false)
internal class NativeRuntimePhaseEvent : Event() {
    @JvmField var phase: String = ""

    @JvmField var machine: Long = -1

    @JvmField var kind: String = ""

    @JvmField var tick: Long = -1

    @JvmField var task: Int = -1

    @JvmField var request: Long = -1

    @JvmField var namespace: String = ""

    @JvmField var capability: String = ""

    @JvmField var operation: Int = -1

    @JvmField var units: Long = -1

    @JvmField var deadlineRemainingNanos: Long = Long.MAX_VALUE
}
