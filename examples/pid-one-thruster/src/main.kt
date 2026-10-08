/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import compukter.concurrent.Tasks
import propulsion.thrusters.CreativeVectorThruster
import sable.physics.Physics

// Capture the construction's rotation-point position at launch, or set a world target below.
const val HOLD_START_POINT = true
const val TARGET_X = 0.0
const val TARGET_Y = 100.0
const val TARGET_Z = 0.0

// 0 uses the engine's saved thrust setting. Tune that setting for approximate hover first.
// Otherwise set the required hover thrust in kN here (mass in kg * gravity / 1000).
const val HOVER_KN = 0.0
const val POSITION_P = 0.15
const val POSITION_I = 0.01
const val POSITION_D = 0.8
const val ATTITUDE_P = 2.0
const val ATTITUDE_D = 1.0
const val MAX_VECTOR = 0.5

fun main() {
    val engine = CreativeVectorThruster.first()
    try {
        val first = Physics.snapshot()
        val mount = engine.mount()
        require(mount.constructionId == first.constructionId) { "Engine must be on this construction" }
        require(mount.forceX == 0 && mount.forceY == 1 && mount.forceZ == 0) { "Mount engine facing local +Y" }
        val state = engine.state()
        val hoverKn = if (HOVER_KN > 0.0) HOVER_KN else state.thrustKn
        require(hoverKn > 0.0 && hoverKn <= state.maxThrustKn) { "Set a positive hover thrust within engine limits" }
        val target =
            if (HOLD_START_POINT) {
                Vector(first.position.x, first.position.y, first.position.z)
            } else {
                Vector(TARGET_X, TARGET_Y, TARGET_Z)
            }
        println("Holding rotation point: ${target.x}, ${target.y}, ${target.z}")
        println("Hover baseline: $hoverKn kN; Ctrl+C stops the program")
        val pidX = AxisPid(POSITION_P, POSITION_I, POSITION_D, 2.0)
        val pidY = AxisPid(POSITION_P, POSITION_I, POSITION_D, 3.0)
        val pidZ = AxisPid(POSITION_P, POSITION_I, POSITION_D, 2.0)
        var previousPosition = Vector(first.position.x, first.position.y, first.position.z)
        var previousTick = first.gameTick
        var velocity = Vector(0.0, 0.0, 0.0)
        var integrate = false
        var lastReport = first.gameTick
        val startTick = first.gameTick
        engine.setVector(0.0, 0.0)
        engine.setThrustKn(hoverKn)
        engine.setThrottle(1.0)
        while (true) {
            Tasks.sleepTicks(1)
            val snapshot = Physics.snapshot()
            require(snapshot.constructionId == first.constructionId && snapshot.dimension == first.dimension)
            val position = Vector(snapshot.position.x, snapshot.position.y, snapshot.position.z)
            val elapsed = snapshot.gameTick - previousTick
            if (snapshot.paused || elapsed < 0L || elapsed > 10L) {
                pidX.reset()
                pidY.reset()
                pidZ.reset()
                previousPosition = position
                previousTick = snapshot.gameTick
                velocity = Vector(0.0, 0.0, 0.0)
                integrate = false
                continue
            }
            if (elapsed == 0L) continue
            val dt = elapsed.toDouble() / 20.0
            // Differentiate the tracked rotation point, not the COM velocity from Physics.
            val measured = (position - previousPosition) * (1.0 / dt)
            val blend = dt / (0.15 + dt)
            velocity = velocity * (1.0 - blend) + measured * blend
            previousPosition = position
            previousTick = snapshot.gameTick
            val error = target - position
            val acceleration =
                Vector(
                    pidX.update(error.x, velocity.x, dt, integrate),
                    pidY.update(error.y, velocity.y, dt, integrate),
                    pidZ.update(error.z, velocity.z, dt, integrate),
                )
            val q = snapshot.orientation
            val omega = snapshot.angularVelocity
            val command =
                steer(
                    Rotation(q.x, q.y, q.z, q.w),
                    Vector(omega.x, omega.y, omega.z),
                    acceleration,
                    hoverKn,
                    state.maxThrustKn,
                )
            engine.setVector(command.vectorX, command.vectorY)
            engine.setThrustKn(command.thrustKn)
            // Delay integral during startup; also freeze it when actuator commands saturate.
            integrate = !command.saturated && snapshot.gameTick - startTick >= 100L
            if (snapshot.gameTick - lastReport >= 40L) {
                println("error: ${error.x}, ${error.y}, ${error.z}; thrust: ${command.thrustKn} kN")
                lastReport = snapshot.gameTick
            }
        }
    } finally {
        engine.close()
    }
}
