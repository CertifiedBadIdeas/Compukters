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
    require(climbHeight >= 5.0 && climbHeight <= 100.0 && holdSeconds >= 1.0 && holdSeconds <= 30.0)
    require(verticalSpeed >= -20.0 && verticalSpeed <= 20.0 && horizontalSpeed >= 0.0 && horizontalSpeed <= 2.0)
    require(positionVelocity.length <= 20.0) { "Invalid observed position motion" }
    require(hoverKn > 0.0 && hoverKn <= maxKn && maxKn <= Double.MAX_VALUE)
    require(dt > 0.0 && dt <= 0.5 && state.settledSeconds >= 0.0 && state.settledSeconds <= 60.0)
    require(state.integral >= -9.81 && state.integral <= 9.81)
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
