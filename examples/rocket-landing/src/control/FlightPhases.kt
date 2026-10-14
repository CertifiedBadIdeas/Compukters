/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import kotlin.math.Vector3d
import kotlin.math.abs

// Pure mission transitions; settling time belongs only to ascent arrival and height hold.
fun nextFlightState(
    state: FlightState,
    height: Double,
    verticalSpeed: Double,
    positionVelocity: Vector3d,
    angularSpeed: Double,
    climbHeight: Double,
    holdSeconds: Double,
    dt: Double,
    centered: Boolean,
): FlightState {
    var phase = state.phase
    var settled = state.settledSeconds
    if (phase == FlightPhase.ASCENT) {
        settled = if (abs(height - climbHeight) <= 0.3 && abs(verticalSpeed) <= 0.3 && centered) settled + dt else 0.0
        if (settled >= 1.0) {
            phase = FlightPhase.HOLD
            settled = 0.0
        }
    } else if (phase == FlightPhase.HOLD) {
        settled = if (abs(height - climbHeight) <= 0.5 && abs(verticalSpeed) <= 0.3 && centered) settled + dt else 0.0
        if (settled >= holdSeconds) {
            phase = FlightPhase.DESCENT
            settled = 0.0
        }
    } else if (phase == FlightPhase.DESCENT) {
        // Solver velocity can differ near contact; cut thrust on the first near-stationary pose sample.
        settled = 0.0
        if (centered && abs(height) <= 0.25 && abs(positionVelocity.y) <= 0.05 &&
            Vector3d(positionVelocity.x, 0.0, positionVelocity.z).length <= 0.05 && angularSpeed <= 0.1
        ) {
            phase = FlightPhase.COMPLETE
        }
    }
    return FlightState(phase, settled, state.integral)
}
