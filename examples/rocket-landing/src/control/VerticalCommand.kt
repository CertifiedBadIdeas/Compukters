/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import kotlin.math.abs
import kotlin.math.cos

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
    profile: FlightProfile = FlightProfile(),
): VerticalCommand {
    val target =
        if (state.phase == FlightPhase.DESCENT) {
            if (centered) {
                // Preserve the slow final approach while braking earlier from a faster cruise.
                -flightBound(approachSpeed(height, profile.descentSpeed, profile), 0.25, if (abs(height) < 0.5) 0.25 else 0.5 * abs(height))
            } else {
                // Return over the pad before the final descent; do not dive through the clearance.
                val limit = if (height < 2.0) 1.0 else profile.descentSpeed
                approachSpeed(2.0 - height, limit, profile)
            }
        } else {
            approachSpeed(climbHeight - height, profile.ascentSpeed, profile)
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
