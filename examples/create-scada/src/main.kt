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
    var previouslyConnected = false
    var requested: Int? = null
    var currentTarget = 0
    var connection: UiIndicator? = null
    var speedText: UiText? = null
    var targetText: UiText? = null
    var controlIndicator: UiIndicator? = null
    var shaftIndicator: UiIndicator? = null
    var loadText: UiText? = null
    var loadGauge: UiGauge? = null
    var alarm: UiIndicator? = null
    var slower: UiButton? = null
    var faster: UiButton? = null
    var stop: UiButton? = null
    var resume: UiButton? = null
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
    while (iterations < 0 || tick < iterations) {
        var rpm = Float.NaN
        var load = Float.NaN
        var target = 0
        var connected = false
        var commandFailed = false
        try {
            if (speedometer == null) speedometer = Speedometer.named(SPEED)
            if (stressometer == null) stressometer = Stressometer.named(STRESS)
            if (controller == null) controller = RotationController.named(CONTROL)
            rpm = speedometer.speed()
            val stress = stressometer.stress()
            val capacity = stressometer.capacity()
            require(finite(rpm) && finite(stress) && finite(capacity) && stress >= 0f && capacity >= 0f)
            load =
                if (capacity > 0f) {
                    (stress.toDouble() * 100.0 / capacity).toFloat()
                } else if (stress == 0f) {
                    0f
                } else {
                    Float.NaN
                }
            target = controller.targetSpeed()
            require(target in -256..256)
            if (target != 0) resumeSpeed = target
            connected = finite(load)
        } catch (error: Exception) {
            speedometer = null
            stressometer = null
            controller = null
            rpm = Float.NaN
            load = Float.NaN
        }
        history.add(rpm)
        // Consume only one click per sample, then discard the bounded backlog. Never replay commands on reconnect.
        val received = screen.pollClick()
        val click = if (connected && previouslyConnected) received else null
        var drained = 0
        while (drained < 64 && screen.pollClick() != null) drained++
        previouslyConnected = connected
        currentTarget = target
        requested = null
        connection!!.label = if (connected) "CONNECTED" else "NO TELEMETRY"
        connection!!.color = if (connected) Colors.GREEN else Colors.RED
        speedText!!.text = "Speed: " + if (finite(rpm)) "${rpm.toInt()} rpm" else "--"
        targetText!!.text = "Target: " + if (connected) "$target rpm" else "--"
        controlIndicator!!.color = if (connected && target != 0) Colors.CYAN else 0x687D8C
        shaftIndicator!!.color = if (finite(rpm) && abs(rpm) > .01f) Colors.GREEN else 0x687D8C
        loadText!!.text = "Load: " + if (finite(load)) "${load.toInt()}%" else "--"
        loadGauge!!.value = load
        loadGauge!!.color = if (load >= ALARM_LOAD) Colors.RED else Colors.GREEN
        slower!!.enabled = connected
        faster!!.enabled = connected
        stop!!.enabled = connected
        resume!!.enabled = connected && target == 0
        alarm!!.label = if (!connected) "ALARM: TELEMETRY LOST" else if (load >= ALARM_LOAD) "ALARM: HIGH LOAD" else "STATUS: NORMAL"
        alarm!!.color = if (!connected || load >= ALARM_LOAD) Colors.RED else Colors.GREEN
        ui.dispatch(click)
        screen.frame {
            clear(0x101820)
            ui.draw(this)
        }
        val command = requested
        if (connected && command != null && command != target) {
            try {
                target = controller!!.setTargetSpeed(command)
                if (target != 0) resumeSpeed = target
            } catch (error: Exception) {
                controller = null
                commandFailed = true
            }
        }
        if (commandFailed) screen.text(12, 350, "ALARM: COMMAND FAILED", Colors.RED)
        tick++
        Tasks.sleepTicks(5)
    }
}

fun finite(value: Float): Boolean = value >= -Float.MAX_VALUE && value <= Float.MAX_VALUE
