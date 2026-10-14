/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import kotlin.math.Rotation3d
import kotlin.math.Vector3d

// Heights refer to the same stationary launch-pad snapshot reference, not terrain below the nozzle.
fun flightCommand(
    state: FlightState,
    height: Double,
    verticalSpeed: Double,
    horizontalSpeed: Double,
    positionVelocity: Vector3d,
    orientation: Rotation3d,
    worldAngularVelocity: Vector3d,
    climbHeight: Double,
    holdSeconds: Double,
    hoverKn: Double,
    maxKn: Double,
    dt: Double,
    padOffset: Vector3d = Vector3d(0.0, 0.0, 0.0),
): FlightCommand {
    require(height >= -0.5 && height <= climbHeight + 10.0) { "Outside vertical flight envelope" }
    require(climbHeight >= 5.0 && climbHeight <= 100.0 && holdSeconds >= 1.0 && holdSeconds <= 30.0) {
        "Invalid flight setup: climb $climbHeight m; hold $holdSeconds s"
    }
    require(verticalSpeed >= -20.0 && verticalSpeed <= 20.0) {
        "Vertical solver speed outside +/-20 m/s: $verticalSpeed m/s"
    }
    require(horizontalSpeed >= 0.0 && horizontalSpeed <= 2.0) {
        "Horizontal solver speed exceeds 2 m/s: $horizontalSpeed m/s"
    }
    require(positionVelocity.length <= 20.0) { "Invalid observed position motion" }
    require(hoverKn > 0.0 && hoverKn <= maxKn && maxKn <= Double.MAX_VALUE) {
        "Invalid thrust limits: hover $hoverKn kN; maximum $maxKn kN"
    }
    require(dt > 0.0 && dt <= 0.5) { "Invalid observation interval: $dt s (maximum 0.5 s)" }
    require(state.settledSeconds >= 0.0 && state.settledSeconds <= 60.0) { "Invalid hold timer: ${state.settledSeconds} s" }
    require(state.integral >= -9.81 && state.integral <= 9.81) { "Invalid speed integral: ${state.integral}" }
    val horizontal = padGuidance(padOffset, positionVelocity)
    val attitude = attitudeCommand(orientation, worldAngularVelocity, horizontal.targetUp)
    val next =
        nextFlightState(
            state,
            height,
            verticalSpeed,
            positionVelocity,
            attitude.angularSpeed,
            climbHeight,
            holdSeconds,
            dt,
            horizontal.centered,
        )
    if (next.phase == FlightPhase.COMPLETE) {
        return FlightCommand(FlightState(next.phase, next.settledSeconds, 0.0), 0.0, 0.0, 0.0, 0.0)
    }
    val vertical = verticalCommand(next, height, verticalSpeed, climbHeight, hoverKn, maxKn, attitude.tilt, dt, horizontal.centered)
    return FlightCommand(
        FlightState(next.phase, next.settledSeconds, vertical.integral),
        vertical.targetSpeed,
        vertical.thrustKn,
        attitude.vectorX,
        attitude.vectorY,
    )
}
