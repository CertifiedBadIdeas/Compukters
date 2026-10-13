/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import kotlin.math.Rotation3d
import kotlin.math.Vector3d
import kotlin.math.abs
import kotlin.math.atan2

value class PointingCommand(
    val vectorX: Double,
    val vectorY: Double,
    val errorRadians: Double,
)

// One +Y engine directly below COM: +X nozzle gives +Z torque; +Z nozzle gives -X torque.
// Pointing controls two axes. Rotation around the engine's +Y axis is not controllable here.
fun pointPrograde(
    orientation: Rotation3d,
    worldAngularVelocity: Vector3d,
    worldDirection: Vector3d,
): PointingCommand {
    val inverse = orientation.inverse()
    val localTarget = inverse.transform(worldDirection)
    val error = Rotation3d.fromTo(Vector3d(0.0, 1.0, 0.0), localTarget)
    val axis = Vector3d(error.x, error.y, error.z)
    val length = axis.length
    val angle = 2.0 * atan2(length, abs(error.w))
    val rotationVector = if (length == 0.0) Vector3d(0.0, 0.0, 0.0) else axis.normalized() * angle
    val rate = inverse.transform(worldAngularVelocity)
    val x = 1.2 * rotationVector.z - 0.6 * rate.z
    val y = -1.2 * rotationVector.x + 0.6 * rate.x
    return PointingCommand(boundedNozzle(x), boundedNozzle(y), angle)
}

private fun boundedNozzle(value: Double): Double {
    require(value >= -Double.MAX_VALUE && value <= Double.MAX_VALUE) { "Nonfinite attitude command" }
    return if (value < -0.5) {
        -0.5
    } else if (value > 0.5) {
        0.5
    } else {
        value
    }
}
