/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import kotlin.math.Rotation3d
import kotlin.math.Vector3d
import kotlin.math.abs
import kotlin.math.atan2

value class AttitudeCommand(
    val vectorX: Double,
    val vectorY: Double,
    val tilt: Double,
    val angularSpeed: Double,
)

// A single +Y engine below COM steers two tilt axes; rotation about +Y is uncontrolled.
fun uprightCommand(
    orientation: Rotation3d,
    worldAngularVelocity: Vector3d,
): AttitudeCommand {
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
    return AttitudeCommand(vectorX, vectorY, tilt, rate.length)
}
