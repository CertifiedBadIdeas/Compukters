/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import kotlin.math.abs
import kotlin.math.sqrt

value class FlightProfile(
    val ascentSpeed: Double = 20.0,
    val descentSpeed: Double = 15.0,
    val brakingAcceleration: Double = 1.0,
    val responseSeconds: Double = 1.0,
)

fun validateFlightProfile(
    profile: FlightProfile,
    climbHeight: Double,
    holdSeconds: Double,
) {
    require(climbHeight >= 5.0 && climbHeight <= 1000.0 && holdSeconds >= 1.0 && holdSeconds <= 30.0) {
        "Invalid flight setup: climb $climbHeight m (5..1000); hold $holdSeconds s (1..30)"
    }
    require(profile.ascentSpeed >= 1.0 && profile.ascentSpeed <= 30.0 && profile.descentSpeed >= 1.0 && profile.descentSpeed <= 25.0) {
        "Invalid cruise speeds: ascent ${profile.ascentSpeed}; descent ${profile.descentSpeed} m/s"
    }
    require(
        profile.brakingAcceleration >= 0.25 && profile.brakingAcceleration <= 2.0 &&
            profile.responseSeconds >= 0.5 && profile.responseSeconds <= 5.0,
    ) { "Invalid braking profile" }
}

// Solve distance = speed * response time + speed² / (2 * braking acceleration).
// Rationalized expression avoids cancellation near the target. This plans a speed, not a thrust guarantee.
fun approachSpeed(
    distance: Double,
    limit: Double,
    profile: FlightProfile,
): Double {
    val reserve = profile.brakingAcceleration * profile.responseSeconds
    val magnitude =
        2.0 * profile.brakingAcceleration * abs(distance) /
            (sqrt(reserve * reserve + 2.0 * profile.brakingAcceleration * abs(distance)) + reserve)
    val bounded = flightBound(magnitude, 0.0, limit)
    return if (distance < 0.0) -bounded else bounded
}

fun flightTimeoutSeconds(
    climbHeight: Double,
    holdSeconds: Double,
    profile: FlightProfile,
): Double {
    validateFlightProfile(profile, climbHeight, holdSeconds)
    // Two cruise-time allowances plus 120 s for acceleration, settling and bounded horizontal recovery.
    return 120.0 + 2.0 * climbHeight / profile.ascentSpeed + 2.0 * climbHeight / profile.descentSpeed + holdSeconds
}
