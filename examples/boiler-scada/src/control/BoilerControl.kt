/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package boiler.control

import kotlin.collections.ArrayList
import kotlin.collections.emptyList

/** All values describe a completed observation, never a partially read set of devices. */
value class BoilerSample(
    val available: Boolean,
    val waterSupply: Float,
    val waterLevel: Int,
    val heatLevel: Int,
    val level: Int,
    val engineRpm: Float,
    val fuelAmount: Int,
    val fuelCapacity: Int,
    val burnTicks: Int,
    val loadStress: Float,
    val loadCapacity: Float,
)

/** Unavailable observations carry no usable readings. */
fun unavailableSample(): BoilerSample = BoilerSample(false, 0f, 0, 0, 0, 0f, 0, 0, 0, 0f, 0f)

enum class Phase { IDLE, STARTING, RUNNING, RECOVERING, DRAINING, COOLING, FAULT }

enum class PowerSource { NONE, BATTERY, STEAM }

enum class FuelMode { OFF, FEED, DRAIN }

value class Outputs(
    val power: PowerSource,
    val fuel: FuelMode,
    val load: Boolean,
)

/** Startup/drain limits count samples; overload/recovery limits count elapsed server ticks. */
class BoilerControl(
    private val stableSamples: Int = 8,
    private val startupLimit: Int = 240,
    private val drainLimit: Int = 240,
    private val overloadDelayTicks: Int = 80,
    private val recoveryLimitTicks: Int = 1200,
) {
    var phase: Phase = Phase.IDLE
        private set
    var alarm: String = ""
        private set
    private var age = 0
    private var stable = 0
    private var lossTicks = -1
    private var recoveryTicks = 0
    private var recoverOnSteam = false

    val outputs: Outputs
        get() =
            when (phase) {
                Phase.STARTING -> Outputs(PowerSource.BATTERY, FuelMode.FEED, false)
                Phase.RUNNING -> Outputs(PowerSource.STEAM, FuelMode.FEED, true)
                Phase.RECOVERING -> Outputs(if (recoverOnSteam) PowerSource.STEAM else PowerSource.BATTERY, FuelMode.FEED, false)
                Phase.DRAINING -> Outputs(PowerSource.BATTERY, FuelMode.DRAIN, false)
                else -> Outputs(PowerSource.NONE, FuelMode.OFF, false)
            }

    val warning: String
        get() =
            if (phase == Phase.RECOVERING) {
                "RECOVERING: LOAD ISOLATED"
            } else if (lossTicks >= 0) {
                "ROTATION/LOAD WAIT: ${(overloadDelayTicks - lossTicks + 19) / 20}s"
            } else {
                ""
            }

    val canStart: Boolean
        get() = alarm.isEmpty() && (phase == Phase.IDLE || phase == Phase.COOLING)

    fun start(sample: BoilerSample) {
        if (canStart && sample.available) enter(Phase.STARTING)
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

    fun observe(
        sample: BoilerSample,
        elapsedTicks: Int = 5,
    ) {
        require(elapsedTicks >= 0)
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
                val overloaded = sample.loadCapacity > 0f && sample.loadStress > sample.loadCapacity
                lossTicks =
                    if (selfPowered(sample) && !overloaded) {
                        -1
                    } else if (lossTicks < 0) {
                        0
                    } else {
                        lossTicks + elapsedTicks
                    }
                if (lossTicks >= overloadDelayTicks) enter(Phase.RECOVERING)
            }

            Phase.RECOVERING -> {
                recoveryTicks += elapsedTicks
                // The isolated load's capacity can be zero. Validate only the engine and service circuit here.
                stable = if (selfPowered(sample)) stable + 1 else 0
                if (stable >= stableSamples && recoverOnSteam) {
                    enter(Phase.RUNNING)
                } else {
                    if (stable >= stableSamples) {
                        recoverOnSteam = true
                        stable = 0
                    }
                    if (recoveryTicks >= recoveryLimitTicks) {
                        alarm = "RECOVERY TIMEOUT"
                        enter(Phase.DRAINING)
                    }
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
        lossTicks = -1
        recoveryTicks = 0
        recoverOnSteam = false
    }
}

enum class Channel { BATTERY, BATTERY_CLUTCH, STEAM_CLUTCH, FUEL_FEED_CLUTCH, FUEL_DRAIN_CLUTCH, LOAD_CLUTCH, WAIT }

value class OutputStep(
    val channel: Channel,
    val value: Int,
)

/**
 * Clutch signals are enables: a redstone torch inverts each signal at the physical clutch.
 * Feed and drain use separate fixed-direction pumps, isolated before switching either pump or power source.
 */
fun outputSteps(
    previous: Outputs,
    next: Outputs,
): List<OutputStep> {
    if (previous == next) return emptyList()
    val steps = ArrayList<OutputStep>()
    if (previous.power == next.power && previous.fuel == next.fuel) {
        steps.add(OutputStep(Channel.LOAD_CLUTCH, if (next.load) 15 else 0))
        steps.add(OutputStep(Channel.WAIT, 2))
        return steps
    }
    steps.add(OutputStep(Channel.LOAD_CLUTCH, 0))
    steps.add(OutputStep(Channel.WAIT, 2))
    steps.add(OutputStep(Channel.FUEL_FEED_CLUTCH, 0))
    steps.add(OutputStep(Channel.FUEL_DRAIN_CLUTCH, 0))
    steps.add(OutputStep(Channel.WAIT, 2))
    if (previous.power != next.power) {
        steps.add(OutputStep(Channel.BATTERY_CLUTCH, 0))
        steps.add(OutputStep(Channel.STEAM_CLUTCH, 0))
        steps.add(OutputStep(Channel.BATTERY, 0))
        steps.add(OutputStep(Channel.WAIT, 2))
    }
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
    when (next.fuel) {
        FuelMode.FEED -> {
            steps.add(OutputStep(Channel.FUEL_FEED_CLUTCH, 15))
        }

        FuelMode.DRAIN -> {
            steps.add(OutputStep(Channel.FUEL_DRAIN_CLUTCH, 15))
        }

        else -> {}
    }
    if (next.load) {
        steps.add(OutputStep(Channel.WAIT, 2))
        steps.add(OutputStep(Channel.LOAD_CLUTCH, 15))
    }
    return steps
}
