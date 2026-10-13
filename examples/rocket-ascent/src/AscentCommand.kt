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

value class AscentCommand(
    val vectorX: Double,
    val vectorY: Double,
    val thrustKn: Double,
    val integral: Double,
    val tiltRadians: Double,
)

enum class AscentPhase { ASCENT, COMPLETE }

fun ascentPhase(
    launchDimension: String,
    dimension: String,
    inDeepSpace: Boolean,
): AscentPhase {
    if (inDeepSpace) return AscentPhase.COMPLETE
    require(dimension == launchDimension) { "Unexpected dimension; restart after checking the construction" }
    return AscentPhase.ASCENT
}

// One +Y engine below COM: +X nozzle gives +Z torque; +Z nozzle gives -X torque.
// The hover setting is calibrated in the actual launch world, not inferred from Sable mass units.
fun ascentCommand(
    orientation: Rotation3d,
    worldAngularVelocity: Vector3d,
    verticalSpeed: Double,
    targetSpeed: Double,
    hoverKn: Double,
    maxKn: Double,
    previousIntegral: Double,
    dt: Double,
): AscentCommand {
    require(hoverKn > 0.0 && hoverKn <= maxKn && maxKn <= Double.MAX_VALUE)
    require(targetSpeed >= 0.0 && targetSpeed <= 100.0)
    require(verticalSpeed >= -1000.0 && verticalSpeed <= 1000.0)
    require(previousIntegral >= -9.81 && previousIntegral <= 9.81)
    require(dt > 0.0 && dt <= 0.5) { "Stale physics observation" }
    val inverse = orientation.inverse()
    val error = Rotation3d.fromTo(Vector3d(0.0, 1.0, 0.0), inverse.transform(Vector3d(0.0, 1.0, 0.0)))
    val axis = Vector3d(error.x, error.y, error.z)
    val length = axis.length
    val tilt = 2.0 * atan2(length, abs(error.w))
    require(tilt <= 1.0471975511965976) { "Tilt exceeds 60 degrees" }
    val rotationVector = if (length == 0.0) Vector3d(0.0, 0.0, 0.0) else axis.normalized() * tilt
    val rate = inverse.transform(worldAngularVelocity)
    require(rate.y >= -Double.MAX_VALUE && rate.y <= Double.MAX_VALUE)
    val x = ascentBound(1.2 * rotationVector.z - 0.6 * rate.z, -0.5, 0.5)
    val y = ascentBound(-1.2 * rotationVector.x + 0.6 * rate.x, -0.5, 0.5)
    val speedError = targetSpeed - verticalSpeed
    val proportional = 0.4 * speedError
    val candidate = ascentBound(previousIntegral + 0.08 * speedError * dt, -9.81, 9.81)
    val raw = hoverKn * (1.0 + (proportional + candidate) / 9.81) / cos(tilt)
    // Integrate only if unsaturated or if the speed error pulls the command out of saturation.
    val integral =
        if ((raw >= 0.0 && raw <= maxKn) || (raw < 0.0 && speedError > 0.0) || (raw > maxKn && speedError < 0.0)) {
            candidate
        } else {
            previousIntegral
        }
    val thrust = hoverKn * (1.0 + (proportional + integral) / 9.81) / cos(tilt)
    // A +Y engine cannot brake upward motion in zero gravity. Cut before the PI integral catches up.
    val limited = if (verticalSpeed > targetSpeed + 1.0) 0.0 else ascentBound(thrust, 0.0, maxKn)
    return AscentCommand(x, y, limited, integral, tilt)
}

private fun ascentBound(
    value: Double,
    minimum: Double,
    maximum: Double,
): Double {
    require(value >= -Double.MAX_VALUE && value <= Double.MAX_VALUE) { "Nonfinite ascent command" }
    return if (value < minimum) {
        minimum
    } else if (value > maximum) {
        maximum
    } else {
        value
    }
}
