/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import kotlin.math.PI
import kotlin.math.Rotation3d
import kotlin.math.Vector3d
import kotlin.math.abs

fun runHorizontalScenarios() {
    val origin = Vector3d(0.0, 0.0, 0.0)
    val displaced = padGuidance(Vector3d(1.0, 0.0, -1.0), origin)
    check(displaced.targetUp.x < 0.0 && displaced.targetUp.z > 0.0 && !displaced.centered)
    val drift = Vector3d(0.1, 0.0, -0.1)
    check(padGuidance(origin, drift).targetUp.dot(drift) < 0.0)
    check(padGuidance(Vector3d(0.2, 0.0, 0.0), Vector3d(0.1, 0.0, 0.0)).centered)
    check(!padGuidance(Vector3d(0.201, 0.0, 0.0), origin).centered)
    check(!padGuidance(origin, Vector3d(0.101, 0.0, 0.0)).centered)
    rejects { padGuidance(Vector3d(Double.NaN, 0.0, 0.0), origin) }
    rejects { padGuidance(origin, Vector3d(0.0, 0.0, Double.POSITIVE_INFINITY)) }
    check(!padGuidance(Vector3d(6.0, 0.0, 0.0), origin).centered)
    rejects { padGuidance(Vector3d(6.01, 0.0, 0.0), origin) }
    val alignedOrientation = Rotation3d.rotationZ(0.04)
    val aligned = attitudeCommand(alignedOrientation, origin, alignedOrientation.transform(Vector3d(0.0, 1.0, 0.0)))
    check(abs(aligned.vectorX) <= 1e-12 && abs(aligned.vectorY) <= 1e-12)
    check(abs(aligned.tilt - 0.04) <= 1e-12)
    // Even with zero attitude error, actual tilt needs additional thrust to hold height.
    check(verticalCommand(FlightState(FlightPhase.HOLD, 0.0, 0.0), 5.0, 0.0, 5.0, 10.0, 20.0, aligned.tilt, 0.05, true).thrustKn > 10.0)
    val uncentered =
        flightCommand(
            FlightState(FlightPhase.DESCENT, 0.0, 0.0),
            0.17,
            0.0,
            0.0,
            origin,
            Rotation3d.identity(),
            origin,
            40.0,
            5.0,
            10.0,
            20.0,
            0.05,
            Vector3d(0.3, 0.17, 0.0),
        )
    check(uncentered.state.phase == FlightPhase.DESCENT && uncentered.targetSpeed > 0.0 && uncentered.thrustKn > 10.0)
    val waiting =
        flightCommand(
            FlightState(FlightPhase.HOLD, 4.99, 0.0),
            40.0,
            0.0,
            0.0,
            origin,
            Rotation3d.identity(),
            origin,
            40.0,
            5.0,
            10.0,
            20.0,
            0.05,
            Vector3d(0.3, 40.0, 0.0),
        )
    check(waiting.state.phase == FlightPhase.HOLD && waiting.state.settledSeconds == 0.0)
    // Independent 3D rigid-body plant. An off-centre nozzle force supplies BOTH force and torque.
    // Isotropic inertia/mass (m²), lever 2 m, nominal gravity; no atmosphere or engine startup model.
    for (scenario in 0 until 6) {
        val inertiaPerMass =
            if (scenario == 0) {
                6.0
            } else if (scenario == 1 || scenario == 4) {
                12.0
            } else if (scenario == 2) {
                24.0
            } else {
                48.0
            }
        val yaw =
            if (scenario == 0) {
                0.0
            } else if (scenario == 1) {
                PI / 2.0
            } else {
                PI / 3.0
            }
        val strongImpulse = scenario >= 4
        val climb =
            if (scenario == 5) {
                200.0
            } else if (strongImpulse) {
                40.0
            } else {
                5.0
            }
        var orientation = Rotation3d.rotationY(yaw)
        var localRate = origin
        var position = origin
        var previousPosition = origin
        var velocity = origin
        var state = FlightState(FlightPhase.ASCENT, 0.0, 0.0)
        var nozzleX = 0.0
        var nozzleY = 0.0
        var disturbed = false
        var hold = false
        var descent = false
        var complete = false
        var maximumDrift = 0.0
        var contactSpeed = 0.0
        var elapsedSeconds = 0.0
        for (step in 0 until 4000) {
            elapsedSeconds = step * 0.05
            if (!disturbed &&
                ((strongImpulse && state.phase == FlightPhase.DESCENT && position.y < climb - 4.0) || (!strongImpulse && position.y > 3.0))
            ) {
                val shift =
                    if (scenario ==
                        0
                    ) {
                        Vector3d(0.6, 0.0, 0.0)
                    } else if (scenario == 1) {
                        Vector3d(0.0, 0.0, -0.6)
                    } else {
                        Vector3d(0.48, 0.0, -0.36)
                    }
                velocity += shift.normalized() * (if (strongImpulse) 1.0 else 0.15)
                disturbed = true
            }
            val observedVelocity = (position - previousPosition) / 0.05
            previousPosition = position
            val output =
                flightCommand(
                    state,
                    position.y,
                    velocity.y,
                    Vector3d(velocity.x, 0.0, velocity.z).length,
                    observedVelocity,
                    orientation,
                    orientation.transform(localRate),
                    climb,
                    5.0,
                    10.0,
                    20.0,
                    0.05,
                    position,
                )
            state = output.state
            check(output.thrustKn >= 0.0 && output.thrustKn <= 20.0)
            check(abs(output.vectorX) <= 0.5 && abs(output.vectorY) <= 0.5)
            if (state.phase == FlightPhase.HOLD) hold = true
            if (state.phase == FlightPhase.DESCENT) descent = true
            if (state.phase == FlightPhase.COMPLETE) {
                complete = true
                break
            }
            // Pinned upstream nozzle: 20% tween/tick, 0.001 snap, +Y thrust direction.
            nozzleX += 0.2 * (output.vectorX - nozzleX)
            nozzleY += 0.2 * (output.vectorY - nozzleY)
            if (abs(output.vectorX - nozzleX) < 0.001) nozzleX = output.vectorX
            if (abs(output.vectorY - nozzleY) < 0.001) nozzleY = output.vectorY
            val localForcePerMass =
                Vector3d(nozzleX * 0.5773502691896257, 1.0, nozzleY * 0.5773502691896257).normalized() * (output.thrustKn / 10.0 * 9.81)
            val torquePerMass = Vector3d(0.0, -2.0, 0.0).cross(localForcePerMass)
            // One 20 Hz rigid-body step, matching the current dev stand's substep count.
            velocity += (orientation.transform(localForcePerMass) - Vector3d(0.0, 9.81, 0.0)) * 0.05
            position += velocity * 0.05
            localRate += torquePerMass / inertiaPerMass * 0.05
            if (localRate.length > 0.0) {
                orientation *= Rotation3d.fromAxisAngle(localRate, localRate.length * 0.05)
            }
            if (position.y < 0.0) {
                if (descent && velocity.y < contactSpeed) contactSpeed = velocity.y
                position = Vector3d(position.x, 0.0, position.z)
                velocity = Vector3d(velocity.x, 0.0, velocity.z)
            }
            val distance = Vector3d(position.x, 0.0, position.z).length
            if (distance > maximumDrift) maximumDrift = distance
            check(distance <= RETURN_CORRIDOR_METERS) { "Coupled drift exceeded corridor: scenario=$scenario; drift=$distance" }
        }
        check(disturbed && hold && descent && complete) {
            "Coupled flight incomplete: scenario=$scenario; phase=${state.phase.name}; height=${position.y}; drift=${Vector3d(
                position.x,
                0.0,
                position.z,
            ).length}"
        }
        check(elapsedSeconds < flightTimeoutSeconds(climb, 5.0, FlightProfile()))
        if (strongImpulse) check(maximumDrift > 2.0)
        check(maximumDrift >= 0.2 && Vector3d(position.x, 0.0, position.z).length <= 0.2)
        check(abs(contactSpeed) <= 0.5 && position.y <= 0.25 && velocity.length <= 0.08 && localRate.length <= 0.1)
    }
}
