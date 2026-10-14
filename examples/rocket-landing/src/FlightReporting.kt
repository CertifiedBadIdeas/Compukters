/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import sable.physics.PhysicsSnapshot
import kotlin.math.Vector3d

fun reportFlight(
    state: FlightState,
    current: PhysicsSnapshot,
    offset: Vector3d,
    command: FlightCommand,
    positionVelocity: Vector3d,
    lateral: Double,
) {
    println(
        "${state.phase.name}: height ${offset.y} m; speed ${current.linearVelocity.y}/${command.targetSpeed} m/s; ${command.thrustKn} kN; drift $lateral m",
    )
    val horizontal = padGuidance(offset, positionVelocity)
    if (!horizontal.centered) {
        println("CENTERING: drift ${horizontal.distance} m; lateral speed ${horizontal.speed} m/s; final descent waits for return.")
    }
    if (state.phase == FlightPhase.DESCENT) {
        println(
            "Landing: pose speed ${positionVelocity.length} m/s; angular ${current.angularVelocity.length} rad/s",
        )
    }
}
