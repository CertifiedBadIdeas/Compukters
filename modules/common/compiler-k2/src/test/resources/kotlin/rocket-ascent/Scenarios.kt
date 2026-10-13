/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import kotlin.math.Rotation3d
import kotlin.math.Vector3d
import kotlin.math.abs

fun command(
    speed: Double,
    target: Double = 20.0,
    integral: Double = 0.0,
): AscentCommand = ascentCommand(Rotation3d.identity(), Vector3d(0.0, 0.0, 0.0), speed, target, 10.0, 20.0, integral, 0.05)

fun rejects(action: () -> Unit) {
    var rejected = false
    try {
        action()
    } catch (_: IllegalArgumentException) {
        rejected = true
    }
    check(rejected)
}

fun main() {
    val hover = command(0.0, 0.0)
    check(abs(hover.thrustKn - 10.0) < 1e-12 && hover.vectorX == 0.0 && hover.vectorY == 0.0)
    check(command(0.0).thrustKn > 10.0)
    check(command(40.0).thrustKn < 10.0)
    val saturated = command(-100.0)
    check(saturated.thrustKn == 20.0 && saturated.integral == 0.0)
    val cutoff = command(100.0)
    check(cutoff.thrustKn == 0.0 && cutoff.integral == 0.0)
    check(command(30.0, integral = 9.81).integral < 9.81)
    check(command(10.0, integral = -9.81).integral > -9.81)
    val tilted = ascentCommand(Rotation3d.rotationZ(0.2), Vector3d(0.0, 0.0, 0.0), 0.0, 0.0, 10.0, 20.0, 0.0, 0.05)
    check(tilted.vectorX < 0.0 && abs(tilted.vectorY) < 1e-12 && tilted.thrustKn > 10.0)
    val spinning = ascentCommand(Rotation3d.identity(), Vector3d(1.0, 0.0, 1.0), 0.0, 0.0, 10.0, 20.0, 0.0, 0.05)
    check(spinning.vectorX == -0.5 && spinning.vectorY == 0.5)
    val tiny = ascentCommand(Rotation3d.rotationX(1e-12), Vector3d(0.0, 0.0, 0.0), 0.0, 0.0, 10.0, 20.0, 0.0, 0.05)
    check(tiny.vectorY > 0.0 && tiny.tiltRadians > 0.0)
    rejects { command(Double.NaN) }
    rejects { command(Double.POSITIVE_INFINITY) }
    rejects { command(0.0, -1.0) }
    rejects { ascentCommand(Rotation3d.rotationX(1.1), Vector3d(0.0, 0.0, 0.0), 0.0, 0.0, 10.0, 20.0, 0.0, 0.05) }
    rejects { ascentCommand(Rotation3d.identity(), Vector3d(0.0, 0.0, 0.0), 0.0, 0.0, 10.0, 20.0, 0.0, 0.6) }
    rejects { ascentCommand(Rotation3d.identity(), Vector3d(0.0, Double.NaN, 0.0), 0.0, 0.0, 10.0, 20.0, 0.0, 0.05) }
    check(ascentPhase("surface", "surface", false) == AscentPhase.ASCENT)
    check(ascentPhase("surface", "space", true) == AscentPhase.COMPLETE)
    rejects { ascentPhase("surface", "unexpected", false) }

    // Ideal one-dimensional plant, not a simulation of startup, nozzle smoothing or world transfer.
    // Settle at launch gravity, then remove it: the integral must reduce thrust instead of accelerating forever.
    var speed = 0.0
    var integral = 0.0
    var target = 0.0
    for (tick in 0 until 2400) {
        target += 0.025
        if (target > 20.0) target = 20.0
        val current = command(speed, target, integral)
        integral = current.integral
        val gravity = if (tick < 1600) 9.81 else 0.0
        speed += (current.thrustKn / 10.0 * 9.81 - gravity) * 0.05
        check(current.thrustKn >= 0.0 && current.thrustKn <= 20.0)
        check(integral >= -9.81 && integral <= 9.81)
        check(speed >= -1.0 && speed < 22.0)
        if (tick == 1599) check(abs(speed - 20.0) < 0.1)
    }
    check(speed >= 20.0 && speed < 22.0)
    check(command(speed, 20.0, integral).thrustKn == 0.0)
    println("rocket ascent ok")
}
