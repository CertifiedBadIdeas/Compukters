/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import compukter.concurrent.Tasks
import cosmonautics.space.Space
import propulsion.thrusters.CreativeVectorThruster
import sable.physics.Physics

// Name this peripheral with the configurator, then Resolve Dependencies before building.
const val ENGINE_NAME = "prograde_engine"

// Start with low thrust, increasing only after the mount and feedback signs are verified.
// Engine produces translation as well as torque; this is not an orbit-insertion burn controller.
const val THRUST_KN = 0.5

fun main() {
    val engine = CreativeVectorThruster.named(ENGINE_NAME)
    try {
        val first = Physics.snapshot()
        val initialGuidance = Space.guidance()
        require(first.dimension == initialGuidance.dimension && !first.paused)
        val mount = engine.mount()
        require(mount.constructionId == first.constructionId) { "Engine must share the computer's construction" }
        require(mount.forceX == 0 && mount.forceY == 1 && mount.forceZ == 0) { "Engine must face local +Y" }
        val engineState = engine.state()
        require(THRUST_KN > 0.0 && THRUST_KN <= engineState.maxThrustKn)
        println("Prograde pointing: ${initialGuidance.frame}; instance ${initialGuidance.instanceId}")
        println("Use a +Y engine directly below COM; Ctrl+C releases control.")
        // Claim control with zero throttle before changing thrust/steering.
        engine.setThrottle(0.0)
        engine.setThrustKn(THRUST_KN)
        var previousTick = -1L
        var lastReport = first.gameTick
        while (true) {
            val physics = Physics.snapshot()
            val guidance = Space.guidance()
            require(physics.constructionId == first.constructionId && physics.dimension == first.dimension)
            require(guidance.dimension == physics.dimension && guidance.instanceId == initialGuidance.instanceId)
            require(guidance.frame == initialGuidance.frame) { "Orbital frame changed; restart and reassess guidance" }
            require(!physics.paused) { "Construction physics is paused" }
            require(
                guidance.gameTick >= physics.gameTick && guidance.gameTick - physics.gameTick <= 2L,
            ) { "Observations are too far apart" }
            val elapsed = if (previousTick < 0L) 1L else physics.gameTick - previousTick
            require(elapsed >= 0L && elapsed <= 10L) { "Stale attitude observation" }
            if (elapsed == 0L) {
                Tasks.sleepTicks(1)
                continue
            }
            previousTick = physics.gameTick
            val command = pointPrograde(physics.orientation, physics.angularVelocity, guidance.prograde)
            engine.setVector(command.vectorX, command.vectorY)
            engine.setThrottle(1.0)
            if (physics.gameTick - lastReport >= 40L) {
                println("Pointing error: ${command.errorRadians} rad; nozzle ${command.vectorX}, ${command.vectorY}")
                lastReport = physics.gameTick
            }
            Tasks.sleepTicks(1)
        }
    } finally {
        engine.close()
    }
}
