/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import compukter.concurrent.Tasks
import compukter.display.*
import create.kinetics.RotationController
import create.kinetics.Speedometer
import create.kinetics.Stressometer
import kotlin.math.*

const val SCREEN = "scada"
const val SPEED = "drive_speed"
const val STRESS = "drive_stress"
const val CONTROL = "drive_control"
const val ALARM_LOAD = 90f

fun area(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
): Rect2i = Rect2i(Vector2i(x, y), Vector2i(width, height))

// Published as one immutable snapshot: device reads may suspend between fields.
data class Telemetry(val sequence: Int, val rpm: Float, val load: Float, val target: Int, val connected: Boolean)

fun main() {
    runDashboard()
}

// A bounded run is also useful when checking a freshly installed stand.
fun runDashboard(iterations: Int = -1) {
    val screen = GraphicalDisplay.named(SCREEN)
    screen.setMode(DisplayMode.HIGH)
    require(screen.width >= 384 && screen.height >= 384) { "Assemble at least a 3x3 screen" }
    val history = DisplayHistory(96)
    var speedometer: Speedometer? = null
    var stressometer: Stressometer? = null
    var controller: RotationController? = null
    var resumeSpeed = 32
    var tick = 0
    var running = true
    var telemetry = Telemetry(0, Float.NaN, Float.NaN, 0, false)
    var previousSequence = -1
    var previousSample = telemetry
    var previouslyConnected = false
    var connected = false
    var requested: Int? = null
    var currentTarget = 0
    var commandFailed = false
    var commandPending = false
    var controlRevision = 0
    val connection: UiIndicator
    val speedText: UiText
    val targetText: UiText
    val controlIndicator: UiIndicator
    val shaftIndicator: UiIndicator
    val loadText: UiText
    val loadGauge: UiGauge
    val alarm: UiIndicator
    val slower: UiButton
    val faster: UiButton
    val stop: UiButton
    val resume: UiButton
    val ui = displayUi(area(0, 0, 384, 384)) {
        at(12 xy 10) { text("CREATE DRIVE", 360 by 24) { color = 0xDAEAF4; scale = 2 } }
        at(12 xy 42) { connection = indicator("NO TELEMETRY", 360 by 14) }
        at(12 xy 64) { speedText = text("Speed: --", 360 by 14) }
        at(12 xy 82) { targetText = text("Target: --", 360 by 14) }
        at(12 xy 108) { controlIndicator = indicator("CONTROL", 100 by 14) }
        at(106 xy 113) { canvas(141 by 1) { onDraw = { line(0, 0, 140, 0, 0x687D8C) } } }
        at(254 xy 108) { shaftIndicator = indicator("SHAFT", 116 by 14) }
        at(12 xy 138) { loadText = text("Load: --", 360 by 14) }
        at(12 xy 158) { loadGauge = gauge(360 by 14) }
        at(12 xy 188) { text("RPM history (-256..256)", 360 by 14) }
        at(12 xy 208) { chart(360 by 84, history) { minimum = -256f; maximum = 256f } }
        at(12 xy 308) {
            row(spacing = 8) {
                slower = button("-16 RPM", 84 by 26) { onClick = { requested = max(-256, currentTarget - 16) } }
                faster = button("+16 RPM", 84 by 26) { onClick = { requested = min(256, currentTarget + 16) } }
                stop = button("STOP", 84 by 26) { onClick = { requested = 0 } }
                resume = button("RESUME", 84 by 26) { onClick = { requested = resumeSpeed } }
            }
        }
        at(12 xy 350) { alarm = indicator("ALARM: TELEMETRY LOST", 360 by 14) }
    }
    val sampler = Tasks.launch {
        while (running) {
            while (running && commandPending) Tasks.sleepTicks(1)
            if (!running) break
            val sampleRevision = controlRevision
            var rpm = Float.NaN
            var load = Float.NaN
            var target = 0
            var connected = false
            try {
                val speedDevice = speedometer ?: Speedometer.named(SPEED)
                val stressDevice = stressometer ?: Stressometer.named(STRESS)
                val controlDevice = controller ?: RotationController.named(CONTROL)
                speedometer = speedDevice
                stressometer = stressDevice
                controller = controlDevice
                rpm = speedDevice.speed()
                val stress = stressDevice.stress()
                val capacity = stressDevice.capacity()
                require(finite(rpm) && finite(stress) && finite(capacity) && stress >= 0f && capacity >= 0f)
                load =
                    if (capacity > 0f) {
                        (stress.toDouble() * 100.0 / capacity).toFloat()
                    } else if (stress == 0f) {
                        0f
                    } else {
                        Float.NaN
                    }
                target = controlDevice.targetSpeed()
                require(target in -256..256)
                connected = finite(load)
            } catch (error: Exception) {
                speedometer = null
                stressometer = null
                controller = null
                rpm = Float.NaN
                load = Float.NaN
            }
            // A target read started before a command must not overwrite its accepted result.
            if (!commandPending && sampleRevision == controlRevision) {
                telemetry = Telemetry(telemetry.sequence + 1, rpm, load, target, connected)
            }
            Tasks.sleepTicks(5)
        }
    }
    while (iterations < 0 || tick < iterations) {
        val sample = telemetry
        var dirty = sample.sequence != previousSequence
        if (dirty) {
            previousSequence = sample.sequence
            dirty = !sameReading(sample.rpm, previousSample.rpm) || !sameReading(sample.load, previousSample.load) ||
                sample.target != previousSample.target || sample.connected != previousSample.connected
            var historyChanged = history.size < history.capacity
            if (!historyChanged) {
                for (index in 0 until history.size) {
                    val next = if (index + 1 < history.size) history[index + 1] else sample.rpm
                    if (!sameReading(history[index], next)) historyChanged = true
                }
            }
            dirty = dirty || historyChanged
            previousSample = sample
            val rpm = sample.rpm
            val load = sample.load
            val target = sample.target
            connected = sample.connected
            currentTarget = target
            if (target != 0) resumeSpeed = target
            history.add(rpm)
            connection.label = if (connected) "CONNECTED" else "NO TELEMETRY"
            connection.color = if (connected) Colors.GREEN else Colors.RED
            speedText.text = "Speed: " + if (finite(rpm)) "${rpm.toInt()} rpm" else "--"
            targetText.text = "Target: " + if (connected) "$target rpm" else "--"
            controlIndicator.color = if (connected && target != 0) Colors.CYAN else 0x687D8C
            shaftIndicator.color = if (finite(rpm) && abs(rpm) > .01f) Colors.GREEN else 0x687D8C
            loadText.text = "Load: " + if (finite(load)) "${load.toInt()}%" else "--"
            loadGauge.value = load
            loadGauge.color = if (load >= ALARM_LOAD) Colors.RED else Colors.GREEN
            slower.enabled = connected
            faster.enabled = connected
            stop.enabled = connected
            resume.enabled = connected && target == 0
            alarm.label = if (!connected) "ALARM: TELEMETRY LOST" else if (load >= ALARM_LOAD) "ALARM: HIGH LOAD" else "STATUS: NORMAL"
            alarm.color = if (!connected || load >= ALARM_LOAD) Colors.RED else Colors.GREEN
        }
        // Do not replay stale input while disconnected or on the first recovered snapshot.
        val received = screen.pollClick()
        val click = if (connected && previouslyConnected) received else null
        if (received != null && (!connected || !previouslyConnected)) {
            var drained = 0
            while (drained < 63 && screen.pollClick() != null) drained++
        }
        previouslyConnected = connected
        requested = null
        ui.dispatch(click)
        val command = requested
        if (connected && command != null && command != currentTarget) {
            controlRevision++
            commandPending = true
            try {
                currentTarget = controller!!.setTargetSpeed(command)
                if (currentTarget != 0) resumeSpeed = currentTarget
                targetText.text = "Target: $currentTarget rpm"
                controlIndicator.color = if (currentTarget != 0) Colors.CYAN else 0x687D8C
                resume.enabled = currentTarget == 0
                commandFailed = false
            } catch (error: Exception) {
                controller = null
                connected = false
                previouslyConnected = false
                slower.enabled = false
                faster.enabled = false
                stop.enabled = false
                resume.enabled = false
                commandFailed = true
            } finally {
                commandPending = false
            }
            dirty = true
        }
        if (commandFailed) {
            alarm.label = "ALARM: COMMAND FAILED"
            alarm.color = Colors.RED
        }
        if (dirty) screen.frame {
            clear(0x101820)
            ui.draw(this)
        }
        tick++
        Tasks.sleepTicks(1)
    }
    running = false
    sampler.join()
}

fun finite(value: Float): Boolean = value >= -Float.MAX_VALUE && value <= Float.MAX_VALUE

fun sameReading(left: Float, right: Float): Boolean = left == right || (!finite(left) && !finite(right))
