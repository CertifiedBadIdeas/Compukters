/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import kotlin.math.PI
import kotlin.math.Rotation3d
import kotlin.math.Vector3d
import kotlin.math.abs

fun main() {
    val up = Vector3d(0.0, 1.0, 0.0)
    val zero = Vector3d(0.0, 0.0, 0.0)
    val aligned = pointPrograde(Rotation3d.identity(), zero, up)
    check(aligned.errorRadians == 0.0 && aligned.vectorX == 0.0 && aligned.vectorY == 0.0)
    // Tiny directions remain meaningful after robust stdlib normalization.
    val tiny = pointPrograde(Rotation3d.identity(), zero, Vector3d(0.0, 1e-300, 1e-300))
    check(tiny.errorRadians > 0.7 && tiny.vectorY < 0.0)
    val towardX = pointPrograde(Rotation3d.identity(), zero, Vector3d(1.0, 0.0, 0.0))
    check(towardX.vectorX == -0.5 && towardX.vectorY == 0.0)
    val towardZ = pointPrograde(Rotation3d.identity(), zero, Vector3d(0.0, 0.0, 1.0))
    check(towardZ.vectorY == -0.5 && towardZ.vectorX == 0.0)
    val reversed = pointPrograde(Rotation3d.identity(), zero, -up)
    check(reversed.errorRadians > 3.14 && abs(reversed.vectorX) == 0.5)
    val damp = pointPrograde(Rotation3d.identity(), Vector3d(1.0, 0.0, 1.0), up)
    check(damp.vectorX == -0.5 && damp.vectorY == 0.5)
    // Equivalent rotated world input must give the same engine-local commands.
    val frame = Rotation3d.rotationX(0.6) * Rotation3d.rotationZ(-0.8)
    val target = Vector3d(1.0, 2.0, 3.0)
    val rate = Vector3d(0.1, -0.2, 0.3)
    val original = pointPrograde(Rotation3d.identity(), rate, target)
    val transformed = pointPrograde(frame, frame.transform(rate), frame.transform(target))
    check(abs(original.vectorX - transformed.vectorX) < 1e-12)
    check(abs(original.vectorY - transformed.vectorY) < 1e-12)
    check(abs(original.errorRadians - transformed.errorRadians) < 1e-12)
    // Ideal two-axis torque plant: checks feedback signs, not upstream thrust/nozzle smoothing.
    var attitude = Rotation3d.rotationZ(PI / 2.0)
    var angularRate = zero
    var step = 0
    while (step < 1000) {
        val command = pointPrograde(attitude, attitude.transform(angularRate), up)
        angularRate += Vector3d(-command.vectorY, 0.0, command.vectorX) * 0.04
        if (angularRate.length != 0.0) attitude *= Rotation3d.fromAxisAngle(angularRate, angularRate.length * 0.02)
        step++
    }
    check(pointPrograde(attitude, attitude.transform(angularRate), up).errorRadians < 0.02)
    check(angularRate.length < 0.02)
    var rejected = false
    try {
        pointPrograde(Rotation3d.identity(), zero, zero)
    } catch (_: IllegalArgumentException) {
        rejected = true
    }
    check(rejected)
    rejected = false
    try {
        pointPrograde(Rotation3d.identity(), Vector3d(Double.NaN, 0.0, 0.0), up)
    } catch (_: IllegalArgumentException) {
        rejected = true
    }
    check(rejected)
    println("orbital pointing ok")
}
