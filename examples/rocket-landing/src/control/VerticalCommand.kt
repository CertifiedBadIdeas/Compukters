/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

value class VerticalCommand(
    val targetSpeed: Double,
    val thrustKn: Double,
    val integral: Double,
)

// Speed PI with thrust saturation, conditional anti-windup and actual tilt compensation.
fun verticalCommand(
    state: FlightState,
    height: Double,
    verticalSpeed: Double,
    climbHeight: Double,
    hoverKn: Double,
    maxKn: Double,
    tilt: Double,
    dt: Double,
    centered: Boolean,
): VerticalCommand {
    val target =
        if (state.phase == FlightPhase.DESCENT) {
            if (centered) {
                // Slow down over the last four metres, allowing the speed loop time to brake.
                -flightBound(0.5 * height, 0.25, 2.0)
            } else {
                // Approach and hold a two-metre clearance until horizontal return is settled.
                flightBound(0.5 * (2.0 - height), -2.0, 1.0)
            }
        } else {
            val difference = climbHeight - height
            val magnitude = flightBound(sqrt(abs(difference)), 0.0, 4.0)
            if (difference < 0.0) -magnitude else magnitude
        }
    val speedError = target - verticalSpeed
    val candidate = flightBound(state.integral + 0.4 * speedError * dt, -9.81, 9.81)
    val raw = hoverKn * (1.0 + (2.0 * speedError + candidate) / 9.81) / cos(tilt)
    val integral =
        if ((raw >= 0.0 && raw <= maxKn) || (raw < 0.0 && speedError > 0.0) ||
            (raw > maxKn && speedError < 0.0)
        ) {
            candidate
        } else {
            state.integral
        }
    val thrust = flightBound(hoverKn * (1.0 + (2.0 * speedError + integral) / 9.81) / cos(tilt), 0.0, maxKn)
    return VerticalCommand(target, thrust, integral)
}
