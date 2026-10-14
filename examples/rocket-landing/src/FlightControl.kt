/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import kotlin.math.Rotation3d
import kotlin.math.Vector3d
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

enum class FlightPhase { ASCENT, HOLD, DESCENT, COMPLETE }

value class FlightState(
    val phase: FlightPhase,
    val settledSeconds: Double,
    val integral: Double,
)

value class FlightCommand(
    val state: FlightState,
    val targetSpeed: Double,
    val thrustKn: Double,
    val vectorX: Double,
    val vectorY: Double,
)

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
): FlightCommand {
    require(height >= -0.5 && height <= climbHeight + 10.0) { "Outside vertical flight envelope" }
    require(climbHeight >= 5.0 && climbHeight <= 100.0 && holdSeconds >= 1.0 && holdSeconds <= 30.0)
    require(verticalSpeed >= -20.0 && verticalSpeed <= 20.0 && horizontalSpeed >= 0.0 && horizontalSpeed <= 2.0)
    require(positionVelocity.length <= 20.0) { "Invalid observed position motion" }
    require(hoverKn > 0.0 && hoverKn <= maxKn && maxKn <= Double.MAX_VALUE)
    require(dt > 0.0 && dt <= 0.5 && state.settledSeconds >= 0.0 && state.settledSeconds <= 60.0)
    require(state.integral >= -9.81 && state.integral <= 9.81)
    val inverse = orientation.inverse()
    val error = Rotation3d.fromTo(Vector3d(0.0, 1.0, 0.0), inverse.transform(Vector3d(0.0, 1.0, 0.0)))
    val axis = Vector3d(error.x, error.y, error.z)
    val tilt = 2.0 * atan2(axis.length, abs(error.w))
    require(tilt <= 0.5235987755982988) { "Tilt exceeds 30 degrees" }
    val rotationVector = if (axis.length == 0.0) Vector3d(0.0, 0.0, 0.0) else axis.normalized() * tilt
    val rate = inverse.transform(worldAngularVelocity)
    require(rate.length <= 2.0) { "Angular speed exceeds flight envelope" }
    val vectorX = flightBound(1.2 * rotationVector.z - 0.6 * rate.z, -0.5, 0.5)
    val vectorY = flightBound(-1.2 * rotationVector.x + 0.6 * rate.x, -0.5, 0.5)
    var phase = state.phase
    var settled = state.settledSeconds
    if (phase == FlightPhase.ASCENT) {
        settled = if (abs(height - climbHeight) <= 0.3 && abs(verticalSpeed) <= 0.3) settled + dt else 0.0
        if (settled >= 1.0) {
            phase = FlightPhase.HOLD
            settled = 0.0
        }
    } else if (phase == FlightPhase.HOLD) {
        settled = if (abs(height - climbHeight) <= 0.5 && abs(verticalSpeed) <= 0.3) settled + dt else 0.0
        if (settled >= holdSeconds) {
            phase = FlightPhase.DESCENT
            settled = 0.0
        }
    } else if (phase == FlightPhase.DESCENT) {
        // Use measured pose displacement for settling; solver velocity can differ near contact.
        // Continue requesting descent until a sustained stop near the known pad reference.
        settled =
            if (abs(height) <= 0.25 && abs(positionVelocity.y) <= 0.05 &&
                Vector3d(positionVelocity.x, 0.0, positionVelocity.z).length <= 0.05 && rate.length <= 0.1
            ) {
                settled + dt
            } else {
                0.0
            }
        if (settled >= 2.0) phase = FlightPhase.COMPLETE
    }
    if (phase == FlightPhase.COMPLETE) return FlightCommand(FlightState(phase, settled, 0.0), 0.0, 0.0, 0.0, 0.0)
    val target =
        if (phase == FlightPhase.DESCENT) {
            // Slow down over the last four metres, allowing the speed loop time to brake.
            -flightBound(0.5 * height, 0.25, 2.0)
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
    return FlightCommand(FlightState(phase, settled, integral), target, thrust, vectorX, vectorY)
}

private fun flightBound(
    value: Double,
    minimum: Double,
    maximum: Double,
): Double {
    require(value >= -Double.MAX_VALUE && value <= Double.MAX_VALUE) { "Nonfinite flight command" }
    return if (value < minimum) {
        minimum
    } else if (value > maximum) {
        maximum
    } else {
        value
    }
}
