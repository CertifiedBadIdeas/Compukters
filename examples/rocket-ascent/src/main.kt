/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import compukter.concurrent.Tasks
import cosmonautics.space.Space
import propulsion.thrusters.CreativeVectorThruster
import sable.physics.Physics

const val ENGINE_NAME = "ascent_engine"

// 0 uses the saved engine setting: calibrate it for approximately level hover before launch.
const val HOVER_KN = 0.0
const val ASCENT_SPEED = 20.0
const val SPEED_RAMP = 0.5

fun main() {
    val initialStatus = Space.status()
    require(!initialStatus.inDeepSpace) { "Start rocket-ascent on a planet surface" }
    val planet = Space.planet()
    require(planet.allowToSpace) { "This planet does not allow entry into Deep Space" }
    val engine = CreativeVectorThruster.named(ENGINE_NAME)
    try {
        engine.setThrottle(0.0)
        val first = Physics.snapshot()
        require(!first.paused && first.dimension == initialStatus.dimension)
        require(first.position.y < planet.transitionHeight) { "Start below the planet's transition height" }
        val mount = engine.mount()
        require(mount.constructionId == first.constructionId) { "Engine must share the computer's construction" }
        require(mount.forceX == 0 && mount.forceY == 1 && mount.forceZ == 0) { "Engine must face local +Y below COM" }
        val state = engine.state()
        val hoverKn = if (HOVER_KN > 0.0) HOVER_KN else state.thrustKn
        require(hoverKn > 0.0 && hoverKn <= state.maxThrustKn)
        val maxKn = if (hoverKn <= state.maxThrustKn / 2.0) hoverKn * 2.0 else state.maxThrustKn
        require(ASCENT_SPEED > 0.0 && ASCENT_SPEED <= 100.0 && SPEED_RAMP > 0.0 && SPEED_RAMP <= 10.0)
        var integral = 0.0
        var targetSpeed = 0.0
        var previousTick = first.gameTick
        var lastReport = previousTick
        println("ASCENT: hover $hoverKn kN; transition Y ${planet.transitionHeight}")
        println("No redstone/other engine inputs. Deep Space ends this experiment, not orbit insertion.")
        while (true) {
            Tasks.sleepTicks(1)
            val status = Space.status()
            if (ascentPhase(first.dimension, status.dimension, status.inDeepSpace) == AscentPhase.COMPLETE) {
                engine.setThrottle(0.0)
                println("COMPLETE: Deep Space detected; ascent control stopped. Check orbital telemetry separately.")
                return
            }
            val physics = Physics.snapshot()
            require(physics.dimension == first.dimension && physics.constructionId == first.constructionId)
            require(!physics.paused) { "Physics paused; ascent stopped" }
            require(physics.gameTick >= status.gameTick && physics.gameTick - status.gameTick <= 2L)
            val elapsed = physics.gameTick - previousTick
            require(elapsed >= 0L && elapsed <= 10L) { "Physics tick gap; ascent stopped" }
            if (elapsed == 0L) continue
            previousTick = physics.gameTick
            val dt = elapsed.toDouble() / 20.0
            targetSpeed += SPEED_RAMP * dt
            if (targetSpeed > ASCENT_SPEED) targetSpeed = ASCENT_SPEED
            val command =
                ascentCommand(
                    physics.orientation,
                    physics.angularVelocity,
                    physics.linearVelocity.y,
                    targetSpeed,
                    hoverKn,
                    maxKn,
                    integral,
                    dt,
                )
            integral = command.integral
            engine.setVector(command.vectorX, command.vectorY)
            engine.setThrustKn(command.thrustKn)
            engine.setThrottle(1.0)
            if (physics.gameTick - lastReport >= 40L) {
                println("ASCENT: Y ${physics.position.y}; speed ${physics.linearVelocity.y}/$targetSpeed m/s; ${command.thrustKn} kN")
                lastReport = physics.gameTick
            }
        }
    } finally {
        // A transferred/disconnected handle may already be invalid. Exit releases the process's leases too.
        try {
            engine.setThrottle(0.0)
        } catch (_: Exception) {
            println("Throttle handle unavailable; check engine and ordinary redstone control.")
        }
        try {
            engine.close()
        } catch (_: Exception) {
            println("Engine handle already unavailable; program control is being released.")
        }
    }
}
