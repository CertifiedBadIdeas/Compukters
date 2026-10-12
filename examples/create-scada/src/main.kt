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
    val widgets = DisplayWidgets(screen)
    val history = DisplayHistory(96)
    var speedometer: Speedometer? = null
    var stressometer: Stressometer? = null
    var controller: RotationController? = null
    var resumeSpeed = 32
    var tick = 0
    var previouslyConnected = false
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
        var requested = target
        screen.frame {
            clear(0x101820)
            text(12, 10, "CREATE DRIVE", 0xDAEAF4, 2)
            widgets.indicator(
                area(12, 42, 360, 14),
                if (connected) "CONNECTED" else "NO TELEMETRY",
                if (connected) Colors.GREEN else Colors.RED,
            )
            text(12, 64, "Speed: " + if (finite(rpm)) "${rpm.toInt()} rpm" else "--")
            text(12, 82, "Target: " + if (connected) "$target rpm" else "--")
            // The diagram shows observed rotation separately from the requested controller speed.
            widgets.indicator(area(12, 108, 100, 14), "CONTROL", if (connected && target != 0) Colors.CYAN else 0x687D8C)
            line(106, 113, 246, 113, 0x687D8C)
            widgets.indicator(area(254, 108, 116, 14), "SHAFT", if (finite(rpm) && abs(rpm) > .01f) Colors.GREEN else 0x687D8C)
            text(12, 138, "Load: " + if (finite(load)) "${load.toInt()}%" else "--")
            widgets.gauge(area(12, 158, 360, 14), load, 0f, 100f, if (load >= ALARM_LOAD) Colors.RED else Colors.GREEN)
            text(12, 188, "RPM history (-256..256)")
            widgets.chart(area(12, 208, 360, 84), history, -256f, 256f)
            if (widgets.button(area(12, 308, 84, 26), "-16 RPM", click, connected)) requested = max(-256, target - 16)
            if (widgets.button(area(104, 308, 84, 26), "+16 RPM", click, connected)) requested = min(256, target + 16)
            if (widgets.button(area(196, 308, 84, 26), "STOP", click, connected)) requested = 0
            if (widgets.button(area(288, 308, 84, 26), "RESUME", click, connected && target == 0)) requested = resumeSpeed
            widgets.indicator(
                area(12, 350, 360, 14),
                if (!connected) {
                    "ALARM: TELEMETRY LOST"
                } else if (load >= ALARM_LOAD) {
                    "ALARM: HIGH LOAD"
                } else {
                    "STATUS: NORMAL"
                },
                if (!connected || load >= ALARM_LOAD) Colors.RED else Colors.GREEN,
            )
        }
        if (connected && requested != target) {
            try {
                target = controller!!.setTargetSpeed(requested)
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
