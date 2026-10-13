/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package boiler.control

import kotlin.collections.ArrayList
import kotlin.collections.emptyList

/** All values describe a completed observation, never a partially read set of devices. */
data class BoilerSample(
    val available: Boolean,
    val waterSupply: Float = 0f,
    val waterLevel: Int = 0,
    val heatLevel: Int = 0,
    val level: Int = 0,
    val engineRpm: Float = 0f,
    val fuelAmount: Int = 0,
    val fuelCapacity: Int = 0,
    val burnTicks: Int = 0,
)

enum class Phase { IDLE, STARTING, RUNNING, DRAINING, COOLING, FAULT }

enum class PowerSource { NONE, BATTERY, STEAM }

enum class FuelDirection { OFF, FEED, DRAIN }

data class Outputs(
    val power: PowerSource,
    val fuel: FuelDirection,
)

/** Time limits count completed samples. Each sample is separated by at least five server ticks. */
class BoilerControl(
    private val stableSamples: Int = 8,
    private val startupLimit: Int = 240,
    private val drainLimit: Int = 240,
) {
    var phase: Phase = Phase.IDLE
        private set
    var alarm: String = ""
        private set
    private var age = 0
    private var stable = 0
    private var bad = 0

    val outputs: Outputs
        get() =
            when (phase) {
                Phase.STARTING -> Outputs(PowerSource.BATTERY, FuelDirection.FEED)
                Phase.RUNNING -> Outputs(PowerSource.STEAM, FuelDirection.FEED)
                Phase.DRAINING -> Outputs(PowerSource.BATTERY, FuelDirection.DRAIN)
                else -> Outputs(PowerSource.NONE, FuelDirection.OFF)
            }

    fun start(sample: BoilerSample) {
        if (phase == Phase.IDLE && sample.available) enter(Phase.STARTING)
    }

    fun stop(sample: BoilerSample) {
        if (sample.available && phase != Phase.DRAINING && phase != Phase.COOLING) enter(Phase.DRAINING)
    }

    fun reset(sample: BoilerSample) {
        if (phase == Phase.FAULT && sample.available && sample.fuelAmount == 0 && sample.heatLevel == 0 && sample.burnTicks == 0) {
            alarm = ""
            enter(Phase.IDLE)
        }
    }

    fun fail(reason: String) {
        alarm = reason
        enter(Phase.FAULT)
    }

    fun observe(sample: BoilerSample) {
        if (!sample.available) {
            if (phase != Phase.IDLE && phase != Phase.FAULT) fail("TELEMETRY LOST")
            return
        }
        age++
        when (phase) {
            Phase.STARTING -> {
                stable = if (selfPowered(sample)) stable + 1 else 0
                if (stable >= stableSamples) {
                    enter(Phase.RUNNING)
                } else if (age >= startupLimit) {
                    fail("START TIMEOUT")
                }
            }

            Phase.RUNNING -> {
                bad = if (selfPowered(sample)) 0 else bad + 1
                if (bad >= 3) {
                    alarm = "SELF POWER LOST"
                    enter(Phase.DRAINING)
                }
            }

            Phase.DRAINING -> {
                stable = if (sample.fuelAmount == 0) stable + 1 else 0
                if (stable >= stableSamples) {
                    enter(Phase.COOLING)
                } else if (age >= drainLimit) {
                    fail("DRAIN TIMEOUT")
                }
            }

            Phase.COOLING -> {
                // A leaking feed line must not silently refill stopped burners.
                if (sample.fuelAmount > 0) {
                    enter(Phase.DRAINING)
                } else if (sample.heatLevel == 0 && sample.burnTicks == 0) {
                    enter(if (alarm.isEmpty()) Phase.IDLE else Phase.FAULT)
                }
            }

            else -> {}
        }
    }

    private fun selfPowered(sample: BoilerSample): Boolean =
        sample.waterSupply > 0f && sample.waterLevel > 0 && sample.heatLevel > 0 && sample.level > 0 &&
            (sample.engineRpm >= 8f || sample.engineRpm <= -8f)

    private fun enter(next: Phase) {
        phase = next
        age = 0
        stable = 0
        bad = 0
    }
}

enum class Channel { BATTERY, BATTERY_CLUTCH, STEAM_CLUTCH, FUEL_CLUTCH, FUEL_REVERSE, WAIT }

data class OutputStep(
    val channel: Channel,
    val value: Int,
)

/** Clutch signals are enables: a redstone torch inverts each signal at the physical clutch. */
fun outputSteps(
    previous: Outputs,
    next: Outputs,
): List<OutputStep> {
    if (previous == next) return emptyList()
    val steps = ArrayList<OutputStep>()
    steps.add(OutputStep(Channel.FUEL_CLUTCH, 0))
    steps.add(OutputStep(Channel.WAIT, 2))
    if (previous.power != next.power) {
        steps.add(OutputStep(Channel.BATTERY_CLUTCH, 0))
        steps.add(OutputStep(Channel.STEAM_CLUTCH, 0))
        steps.add(OutputStep(Channel.BATTERY, 0))
        steps.add(OutputStep(Channel.WAIT, 2))
    }
    steps.add(OutputStep(Channel.FUEL_REVERSE, if (next.fuel == FuelDirection.DRAIN) 15 else 0))
    steps.add(OutputStep(Channel.WAIT, 2))
    if (previous.power != next.power) {
        when (next.power) {
            PowerSource.BATTERY -> {
                steps.add(OutputStep(Channel.BATTERY, 15))
                steps.add(OutputStep(Channel.BATTERY_CLUTCH, 15))
            }

            PowerSource.STEAM -> {
                steps.add(OutputStep(Channel.STEAM_CLUTCH, 15))
                steps.add(OutputStep(Channel.WAIT, 2))
                // No discharge signal: the connected battery charges from the engine.
                steps.add(OutputStep(Channel.BATTERY_CLUTCH, 15))
            }

            else -> {}
        }
        steps.add(OutputStep(Channel.WAIT, 2))
    }
    if (next.fuel != FuelDirection.OFF) steps.add(OutputStep(Channel.FUEL_CLUTCH, 15))
    return steps
}
