/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

// This example uses arithmetic only: Guest kotlin.math is not available yet.
data class Vector(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    operator fun plus(other: Vector) = Vector(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: Vector) = Vector(x - other.x, y - other.y, z - other.z)

    operator fun times(scale: Double) = Vector(x * scale, y * scale, z * scale)

    fun dot(other: Vector) = x * other.x + y * other.y + z * other.z

    fun cross(other: Vector) = Vector(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)

    fun unit(): Vector = this * (1.0 / root(dot(this)))
}

fun limit(
    value: Double,
    minimum: Double,
    maximum: Double,
): Double {
    require(value >= -Double.MAX_VALUE && value <= Double.MAX_VALUE)
    return if (value < minimum) {
        minimum
    } else if (value > maximum) {
        maximum
    } else {
        value
    }
}

// Bounded inputs: norms of unit vectors, accelerations and nozzle slopes.
fun root(value: Double): Double {
    require(value > 0.0 && value <= 10000.0)
    var result = if (value > 1.0) value else 1.0
    var iteration = 0
    while (iteration < 20) {
        result = 0.5 * (result + value / result)
        iteration += 1
    }
    return result
}

class Rotation(
    val x: Double,
    val y: Double,
    val z: Double,
    val w: Double,
) {
    fun world(local: Vector): Vector {
        val axis = Vector(x, y, z)
        val twice = axis.cross(local) * 2.0
        return local + twice * w + axis.cross(twice)
    }

    fun local(world: Vector): Vector = Rotation(-x, -y, -z, w).world(world)
}

class AxisPid(
    val kp: Double,
    val ki: Double,
    val kd: Double,
    val maximum: Double,
) {
    private var integral = 0.0

    fun reset() {
        integral = 0.0
    }

    fun update(
        error: Double,
        velocity: Double,
        dt: Double,
        integrate: Boolean,
    ): Double {
        val candidate = limit(integral + ki * error * dt, -1.5, 1.5)
        val output = kp * error + candidate - kd * velocity
        // Conditional integration: do not accumulate an error that pushes into saturation.
        if (integrate && !(output > maximum && error > 0.0) && !(output < -maximum && error < 0.0)) {
            integral = candidate
        }
        return limit(kp * error + integral - kd * velocity, -maximum, maximum)
    }
}

data class Command(
    val vectorX: Double,
    val vectorY: Double,
    val thrustKn: Double,
    val saturated: Boolean,
)

// Requires an upward-facing engine BELOW the centre of mass and close to its vertical axis.
// Positive nozzle X/Y generate local +X/+Z force; because the engine is below COM,
// these produce +Z/-X torque. The signs deliberately differ from direct force steering.
fun steer(
    rotation: Rotation,
    angularVelocity: Vector,
    acceleration: Vector,
    hoverKn: Double,
    maxKn: Double,
): Command {
    val up = rotation.world(Vector(0.0, 1.0, 0.0))
    require(up.y > 0.5) // Initial tilt and recovery must stay below 60 degrees.
    val vertical = 9.81 + acceleration.y
    val horizontalLimit = vertical * 0.25
    val x = limit(acceleration.x, -horizontalLimit, horizontalLimit)
    val z = limit(acceleration.z, -horizontalLimit, horizontalLimit)
    val desiredUp = Vector(x, vertical, z).unit()
    val error = rotation.local(up.cross(desiredUp))
    val rate = rotation.local(angularVelocity)
    val rawX = ATTITUDE_P * error.z - ATTITUDE_D * rate.z
    val rawY = -ATTITUDE_P * error.x + ATTITUDE_D * rate.x
    val vectorX = limit(rawX, -MAX_VECTOR, MAX_VECTOR)
    val vectorY = limit(rawY, -MAX_VECTOR, MAX_VECTOR)
    val slope = 0.5773502691896257 // tan(30 degrees), from Propulsion's nozzle geometry.
    val localForce = Vector(vectorX * slope, 1.0, vectorY * slope).unit()
    val verticalFraction = rotation.world(localForce).y
    require(verticalFraction > 0.2)
    val rawThrust = hoverKn * (vertical / 9.81) / verticalFraction
    return Command(
        vectorX,
        vectorY,
        limit(rawThrust, 0.0, maxKn),
        x != acceleration.x || z != acceleration.z || vectorX != rawX || vectorY != rawY || rawThrust > maxKn,
    )
}
