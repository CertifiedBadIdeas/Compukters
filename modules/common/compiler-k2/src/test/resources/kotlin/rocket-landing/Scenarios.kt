/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import kotlin.math.Rotation3d
import kotlin.math.Vector3d
import kotlin.math.abs

fun rejects(block: () -> Unit) {
    var rejected = false
    try {
        block()
    } catch (_: IllegalArgumentException) {
        rejected = true
    }
    check(rejected)
}

fun command(
    state: FlightState,
    height: Double,
    speed: Double,
    climb: Double = 40.0,
    positionVelocity: Vector3d = Vector3d(0.0, speed, 0.0),
    angularVelocity: Vector3d = Vector3d(0.0, 0.0, 0.0),
): FlightCommand =
    flightCommand(
        state,
        height,
        speed,
        0.0,
        positionVelocity,
        Rotation3d.identity(),
        angularVelocity,
        climb,
        5.0,
        10.0,
        20.0,
        0.05,
    )

fun runScenarios() {
    val descent = FlightState(FlightPhase.DESCENT, 0.0, 0.0)
    val falling = command(descent, 10.0, -5.0)
    check(falling.thrustKn > 10.0) // Arrest descent, unlike ascent's nonnegative-speed contract.
    check(command(descent, 10.0, 0.0).thrustKn < 10.0)
    check(command(descent, 0.01, -0.25).targetSpeed == -0.25)
    check(command(descent, 0.1, -2.0).thrustKn > 10.0)
    check(command(descent, 0.0, 0.0).state.phase == FlightPhase.COMPLETE)
    check(command(descent, 0.25, 0.05).state.phase == FlightPhase.COMPLETE)
    check(command(descent, 0.251, 0.0).state.phase == FlightPhase.DESCENT)
    check(command(descent, 0.17, 0.051).state.phase == FlightPhase.DESCENT)
    check(command(descent, 0.17, 0.0, positionVelocity = Vector3d(0.051, 0.0, 0.0)).state.phase == FlightPhase.DESCENT)
    check(command(descent, 0.17, 0.0, angularVelocity = Vector3d(0.0, 0.101, 0.0)).state.phase == FlightPhase.DESCENT)
    check(command(FlightState(FlightPhase.DESCENT, 1.99, 0.0), 0.0, 0.0).state.phase == FlightPhase.COMPLETE)
    check(command(FlightState(FlightPhase.DESCENT, 1.99, 0.0), 1.0, 0.0).state.settledSeconds == 0.0)
    check(command(FlightState(FlightPhase.DESCENT, 1.99, 0.0), 0.0, -0.2).state.settledSeconds == 0.0)
    // A near-pad pose barely moves while the instantaneous solver still reports the approach speed.
    val stopped =
        flightCommand(
            descent,
            0.17,
            -0.25,
            0.0,
            Vector3d(0.0, 0.007, 0.0),
            Rotation3d.identity(),
            Vector3d(0.0, 0.0, 0.0),
            40.0,
            5.0,
            10.0,
            20.0,
            0.05,
        )
    check(stopped.state.phase == FlightPhase.COMPLETE)
    check(stopped.thrustKn == 0.0 && stopped.vectorX == 0.0 && stopped.vectorY == 0.0)
    val moving =
        flightCommand(
            FlightState(FlightPhase.DESCENT, 1.99, 0.0),
            0.17,
            0.0,
            0.0,
            Vector3d(0.0, -0.25, 0.0),
            Rotation3d.identity(),
            Vector3d(0.0, 0.0, 0.0),
            40.0,
            5.0,
            10.0,
            20.0,
            0.05,
        )
    check(moving.state.phase == FlightPhase.DESCENT && moving.state.settledSeconds == 0.0)
    val cutoff = command(FlightState(FlightPhase.COMPLETE, 2.0, 0.0), 0.0, 0.0)
    check(cutoff.thrustKn == 0.0 && cutoff.vectorX == 0.0 && cutoff.vectorY == 0.0)
    val saturated = command(FlightState(FlightPhase.ASCENT, 0.0, 9.81), 0.0, -20.0)
    check(saturated.thrustKn == 20.0 && saturated.state.integral == 9.81)
    val noWindup = command(FlightState(FlightPhase.DESCENT, 0.0, -9.81), 10.0, 20.0)
    check(noWindup.thrustKn == 0.0 && noWindup.state.integral == -9.81)
    val tilt =
        flightCommand(
            descent,
            10.0,
            0.0,
            0.0,
            Vector3d(0.0, 0.0, 0.0),
            Rotation3d.rotationZ(0.1),
            Vector3d(0.0, 0.0, 0.0),
            40.0,
            5.0,
            10.0,
            20.0,
            0.05,
        )
    check(tilt.vectorX < 0.0)
    rejects { command(descent, Double.NaN, 0.0) }
    rejects { command(descent, -0.6, 0.0) }
    rejects { command(descent, 10.0, Double.POSITIVE_INFINITY) }
    rejects {
        flightCommand(
            descent,
            10.0,
            0.0,
            0.0,
            Vector3d(0.0, 0.0, 0.0),
            Rotation3d.rotationX(0.6),
            Vector3d(0.0, 0.0, 0.0),
            40.0,
            5.0,
            10.0,
            20.0,
            0.05,
        )
    }
    rejects {
        flightCommand(
            descent,
            10.0,
            0.0,
            0.0,
            Vector3d(0.0, 0.0, 0.0),
            Rotation3d.identity(),
            Vector3d(0.0, Double.NaN, 0.0),
            40.0,
            5.0,
            10.0,
            20.0,
            0.05,
        )
    }
    rejects {
        flightCommand(
            descent,
            10.0,
            0.0,
            0.0,
            Vector3d(0.0, 0.0, 0.0),
            Rotation3d.identity(),
            Vector3d(0.0, 0.0, 0.0),
            40.0,
            5.0,
            10.0,
            20.0,
            0.6,
        )
    }
    // Independent ideal plant: calibrated upward force, gravity, and inelastic contact with a flat pad.
    // Include a 10% hover calibration error; PI must recover and still complete the full flight.
    for (climb in listOf(5.0, 40.0)) {
        for (gravity in listOf(8.829, 9.81, 10.791)) {
            var state = FlightState(FlightPhase.ASCENT, 0.0, 0.0)
            var height = 0.0
            var speed = 0.0
            var maximumHeight = 0.0
            var contactSpeed = 0.0
            var hold = false
            var descentSeen = false
            var complete = false
            for (step in 0 until 2400) {
                val output = command(state, height, speed, climb)
                state = output.state
                check(output.thrustKn >= 0.0 && output.thrustKn <= 20.0)
                if (state.phase == FlightPhase.HOLD) hold = true
                if (state.phase == FlightPhase.DESCENT) descentSeen = true
                if (state.phase == FlightPhase.COMPLETE) {
                    complete = true
                    break
                }
                speed += (output.thrustKn / 10.0 * 9.81 - gravity) * 0.05
                height += speed * 0.05
                if (height < 0.0) {
                    if (descentSeen && speed < contactSpeed) contactSpeed = speed
                    height = 0.0
                    speed = 0.0
                }
                if (height > maximumHeight) maximumHeight = height
            }
            check(
                hold && descentSeen && complete,
            ) { "Flight did not complete: gravity=$gravity; phase=${state.phase}; height=$height; speed=$speed" }
            check(maximumHeight >= climb - 0.3 && maximumHeight < climb + 2.0) { "Flight height exceeded envelope: $maximumHeight" }
            check(
                abs(contactSpeed) <= 0.5 && height <= 0.15 && abs(speed) <= 0.1,
            ) { "Contact too fast: $contactSpeed; climb=$climb; gravity=$gravity; height=$height; speed=$speed" }
        }
    }
    println("rocket landing ok")
}

fun main() {
    try {
        runScenarios()
    } catch (failure: Exception) {
        println("Failure: " + failure.message)
        throw failure
    }
}
