/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import compukter.concurrent.Tasks
import propulsion.thrusters.CreativeVectorThruster
import sable.physics.Physics
import kotlin.math.Vector3d

const val ENGINE_NAME = "ascent_engine"

// 0 uses the engine's saved thrust: calibrate for hover before running.
const val HOVER_KN = 0.0
const val CLIMB_HEIGHT = 200.0
const val HOLD_SECONDS = 5.0
const val ASCENT_SPEED = 20.0
const val DESCENT_SPEED = 15.0

fun main() {
    val profile = FlightProfile(ASCENT_SPEED, DESCENT_SPEED)
    val timeout = flightTimeoutSeconds(CLIMB_HEIGHT, HOLD_SECONDS, profile)
    val engine = CreativeVectorThruster.named(ENGINE_NAME)
    try {
        val setup = prepareFlight(engine)
        val first = setup.reference
        val hover = setup.hoverKn
        val maximum = setup.maximumKn
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
            require(current.gameTick - first.gameTick <= (timeout * 20.0).toLong()) { "Flight timeout: $timeout s" }
            if (elapsed == 0L) continue
            val dt = elapsed.toDouble() / 20.0
            val positionVelocity = (current.position - previousPosition) / dt
            previousPosition = current.position
            previousTick = current.gameTick
            val offset = current.position - first.position
            val lateral = Vector3d(offset.x, 0.0, offset.z).length
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
                    offset,
                    profile,
                )
            val changed = command.state.phase != state.phase
            state = command.state
            if (state.phase == FlightPhase.COMPLETE) {
                engine.setThrottle(0.0)
                println("COMPLETE: stationary near pad reference; releasing engine control.")
                break
            }
            applyFlightCommand(engine, command)
            if (changed || current.gameTick - lastReport >= 20L) {
                reportFlight(state, current, offset, command, positionVelocity, lateral)
                lastReport = current.gameTick
            }
        }
    } finally {
        releaseFlightEngine(engine)
    }
}
