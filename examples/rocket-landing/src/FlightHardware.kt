/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import cosmonautics.space.Space
import propulsion.thrusters.CreativeVectorThruster
import sable.physics.Physics
import sable.physics.PhysicsSnapshot
import kotlin.math.Vector3d

value class FlightSetup(
    val reference: PhysicsSnapshot,
    val hoverKn: Double,
    val maximumKn: Double,
)

// All peripheral ownership stays in main's try/finally; preparation never opens a second handle.
fun prepareFlight(engine: CreativeVectorThruster): FlightSetup {
    val status = Space.status()
    require(!status.inDeepSpace) { "Start rocket-landing on a planet's clear launch pad" }
    val planet = Space.planet()
    engine.setThrottle(0.0)
    val first = Physics.snapshot()
    require(!first.paused && first.dimension == status.dimension)
    require(first.linearVelocity.length <= 0.1 && first.angularVelocity.length <= 0.1) { "Start stationary on the pad" }
    require(first.orientation.transform(Vector3d(0.0, 1.0, 0.0)).y >= 0.999) { "Start upright" }
    require(first.position.y + CLIMB_HEIGHT + 10.0 < planet.transitionHeight) { "Keep flight below Deep Space transition" }
    val mount = engine.mount()
    require(mount.constructionId == first.constructionId)
    require(mount.forceX == 0 && mount.forceY == 1 && mount.forceZ == 0) { "Mount +Y engine below center of mass" }
    val saved = engine.state()
    val hover = if (HOVER_KN > 0.0) HOVER_KN else saved.thrustKn
    require(hover > 0.0 && hover <= saved.maxThrustKn / 1.2) { "Calibrate hover with at least 20% thrust reserve" }
    val maximum = if (hover <= saved.maxThrustKn / 2.0) hover * 2.0 else saved.maxThrustKn
    return FlightSetup(first, hover, maximum)
}

fun applyFlightCommand(
    engine: CreativeVectorThruster,
    command: FlightCommand,
) {
    engine.setVector(command.vectorX, command.vectorY)
    engine.setThrustKn(command.thrustKn)
    engine.setThrottle(1.0)
}

fun releaseFlightEngine(engine: CreativeVectorThruster) {
    try {
        engine.setThrottle(0.0)
    } catch (_: Exception) {
        println("Throttle command unavailable; check engine")
    }
    try {
        engine.close()
    } catch (_: Exception) {
        println("Engine handle unavailable; process exit releases control")
    }
}
