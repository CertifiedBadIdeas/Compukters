/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import compukter.concurrent.Tasks
import cosmonautics.space.Space
import propulsion.thrusters.CreativeVectorThruster
import sable.physics.Physics
import kotlin.math.Vector3d

const val ENGINE_NAME = "ascent_engine"

// 0 uses the engine's saved thrust: calibrate for hover before running.
const val HOVER_KN = 0.0
const val CLIMB_HEIGHT = 40.0
const val HOLD_SECONDS = 5.0
const val MAX_DRIFT = 2.0
const val MAX_FLIGHT_SECONDS = 120.0

fun main() {
    val status = Space.status()
    require(!status.inDeepSpace) { "Start rocket-landing on a planet's clear launch pad" }
    val planet = Space.planet()
    val engine = CreativeVectorThruster.named(ENGINE_NAME)
    try {
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
        var state = FlightState(FlightPhase.ASCENT, 0.0, 0.0)
        var previousPosition = first.position
        var previousTick = first.gameTick
        var lastReport = previousTick
        println("PAD reference Y ${first.position.y}; climb $CLIMB_HEIGHT m; hover $hover kN")
        println("Clear pad, no other engine/redstone inputs. Ctrl+C releases control.")
        while (true) {
            Tasks.sleepTicks(1)
            val current = Physics.snapshot()
            require(current.dimension == first.dimension && current.constructionId == first.constructionId) { "Flight context changed" }
            require(!current.paused) { "Physics paused" }
            val elapsed = current.gameTick - previousTick
            require(elapsed >= 0L && elapsed <= 10L) { "Stale flight feedback" }
            require(current.gameTick - first.gameTick <= (MAX_FLIGHT_SECONDS * 20.0).toLong()) { "Flight timeout" }
            if (elapsed == 0L) continue
            val dt = elapsed.toDouble() / 20.0
            val positionVelocity = (current.position - previousPosition) / dt
            previousPosition = current.position
            previousTick = current.gameTick
            val offset = current.position - first.position
            val lateral = Vector3d(offset.x, 0.0, offset.z).length
            require(lateral <= MAX_DRIFT) { "Outside pad corridor; horizontal navigation unavailable" }
            val horizontalSpeed = Vector3d(current.linearVelocity.x, 0.0, current.linearVelocity.z).length
            val command =
                flightCommand(
                    state,
                    offset.y,
                    current.linearVelocity.y,
                    horizontalSpeed,
                    positionVelocity,
                    current.orientation,
                    current.angularVelocity,
                    CLIMB_HEIGHT,
                    HOLD_SECONDS,
                    hover,
                    maximum,
                    dt,
                )
            val changed = command.state.phase != state.phase
            state = command.state
            if (state.phase == FlightPhase.COMPLETE) {
                engine.setThrottle(0.0)
                println("COMPLETE: stationary near pad reference; releasing engine control.")
                break
            }
            engine.setVector(command.vectorX, command.vectorY)
            engine.setThrustKn(command.thrustKn)
            engine.setThrottle(1.0)
            if (changed || current.gameTick - lastReport >= 20L) {
                println(
                    "${state.phase.name}: height ${offset.y} m; speed ${current.linearVelocity.y}/${command.targetSpeed} m/s; ${command.thrustKn} kN; drift $lateral m",
                )
                if (state.phase == FlightPhase.DESCENT) {
                    println(
                        "Landing: pose speed ${positionVelocity.length} m/s; angular ${current.angularVelocity.length} rad/s",
                    )
                }
                lastReport = current.gameTick
            }
        }
    } finally {
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
}
