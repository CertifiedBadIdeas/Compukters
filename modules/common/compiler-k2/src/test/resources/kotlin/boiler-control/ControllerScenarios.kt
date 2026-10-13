/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import boiler.control.BoilerControl
import boiler.control.BoilerSample
import boiler.control.Channel
import boiler.control.FuelMode
import boiler.control.Outputs
import boiler.control.Phase
import boiler.control.PowerSource
import boiler.control.outputSteps
import boiler.control.unavailableSample
import compukter.compiler.CompilationFailureReason
import compukter.compiler.CompilationResult
import compukter.filesystem.FileInfo
import compukter.filesystem.FileKind
import compukter.filesystem.Path
import compukter.process.ProcessFailureReason
import compukter.process.ProcessResult
import compukter.terminal.TerminalEvent

class ControllerScenarios {
    private val cold = BoilerSample(true, 0f, 0, 0, 0, 0f, 0, 4000, 0)
    private val hot = BoilerSample(true, 20f, 1, 1, 1, 16f, 1200, 4000, 0)
    private val off = Outputs(PowerSource.NONE, FuelMode.OFF)

    fun explicitStartup() {
        val control = BoilerControl(stableSamples = 3)
        repeat(10) { control.observe(hot) }
        assertEquals(off, control.outputs)
        control.start(unavailableSample())
        assertEquals(Phase.IDLE, control.phase)
        control.start(cold)
        assertEquals(Outputs(PowerSource.BATTERY, FuelMode.FEED), control.outputs)
        repeat(2) { control.observe(hot) }
        control.observe(sample(hot, engineRpm = 0f))
        repeat(2) { control.observe(hot) }
        assertEquals(Phase.STARTING, control.phase)
        control.observe(hot)
        assertEquals(Outputs(PowerSource.STEAM, FuelMode.FEED), control.outputs)
    }

    fun residualFuelAndCooling() {
        val control = BoilerControl(stableSamples = 3)
        control.start(cold)
        control.stop(hot)
        assertEquals(Outputs(PowerSource.BATTERY, FuelMode.DRAIN), control.outputs)
        repeat(5) { control.observe(sample(hot, fuelAmount = 1)) }
        assertEquals(Phase.DRAINING, control.phase)
        repeat(2) { control.observe(sample(hot, fuelAmount = 0)) }
        control.observe(sample(hot, fuelAmount = 1))
        repeat(3) { control.observe(sample(hot, fuelAmount = 0)) }
        assertEquals(Phase.COOLING, control.phase)
        assertEquals(off, control.outputs)
        control.observe(cold)
        assertEquals(Phase.IDLE, control.phase)
    }

    fun restartDuringCooling() {
        val control = BoilerControl(stableSamples = 2)
        control.stop(hot)
        val burning = sample(hot, fuelAmount = 0, burnTicks = 100)
        repeat(2) { control.observe(burning) }
        assertEquals(Phase.COOLING, control.phase)
        assertTrue(control.canStart)
        control.start(unavailableSample())
        assertEquals(Phase.COOLING, control.phase)
        control.start(burning)
        assertEquals(Phase.STARTING, control.phase)
        assertEquals(Outputs(PowerSource.BATTERY, FuelMode.FEED), control.outputs)
        repeat(2) { control.observe(hot) }
        assertEquals(Phase.RUNNING, control.phase)
        assertEquals(Outputs(PowerSource.STEAM, FuelMode.FEED), control.outputs)

        val fault = BoilerControl(stableSamples = 1)
        fault.start(cold)
        fault.observe(hot)
        repeat(3) { fault.observe(sample(hot, waterSupply = 0f)) }
        fault.observe(burning)
        assertEquals(Phase.COOLING, fault.phase)
        assertFalse(fault.canStart)
        fault.start(burning)
        assertEquals(Phase.COOLING, fault.phase)
        assertEquals(off, fault.outputs)
    }

    fun remainingBurnTime() {
        val control = BoilerControl(stableSamples = 1)
        control.stop(cold)
        control.observe(sample(cold, burnTicks = 100))
        assertEquals(Phase.COOLING, control.phase)
        control.observe(sample(cold, burnTicks = 1))
        assertEquals(Phase.COOLING, control.phase)
        control.observe(cold)
        assertEquals(Phase.IDLE, control.phase)
    }

    fun telemetryFault() {
        val control = BoilerControl()
        control.start(cold)
        control.observe(sample(cold, available = false))
        assertEquals(Phase.FAULT, control.phase)
        assertEquals(off, control.outputs)
        control.observe(hot)
        control.start(hot)
        control.reset(hot)
        assertEquals(Phase.FAULT, control.phase)
        control.stop(hot)
        assertEquals(Phase.DRAINING, control.phase)
    }

    fun startupAndDrainTimeouts() {
        val starting = BoilerControl(startupLimit = 3)
        starting.start(cold)
        repeat(3) { starting.observe(cold) }
        assertEquals("START TIMEOUT", starting.alarm)
        assertEquals(off, starting.outputs)
        val draining = BoilerControl(drainLimit = 3)
        draining.stop(hot)
        repeat(3) { draining.observe(hot) }
        assertEquals("DRAIN TIMEOUT", draining.alarm)
        assertEquals(off, draining.outputs)
        draining.reset(cold)
        assertEquals(Phase.IDLE, draining.phase)
        assertEquals(off, draining.outputs)
    }

    fun lostSelfPowerAndLeakingFeed() {
        val control = BoilerControl(stableSamples = 2)
        control.start(cold)
        repeat(2) { control.observe(hot) }
        repeat(3) { control.observe(sample(hot, waterSupply = 0f)) }
        assertEquals(Phase.DRAINING, control.phase)
        assertEquals(PowerSource.BATTERY, control.outputs.power)
        repeat(2) { control.observe(sample(hot, fuelAmount = 0)) }
        control.observe(hot)
        assertEquals(Phase.DRAINING, control.phase)
        repeat(2) { control.observe(cold) }
        control.observe(cold)
        assertEquals(Phase.FAULT, control.phase)
        assertEquals("SELF POWER LOST", control.alarm)
    }

    fun outputInterlocks() {
        val states =
            listOf(
                off,
                Outputs(PowerSource.BATTERY, FuelMode.FEED),
                Outputs(PowerSource.STEAM, FuelMode.FEED),
                Outputs(PowerSource.BATTERY, FuelMode.DRAIN),
            )
        for (previous in states) {
            for (next in states) {
                val signals =
                    mutableMapOf(
                        Channel.BATTERY to if (previous.power == PowerSource.BATTERY) 15 else 0,
                        Channel.BATTERY_CLUTCH to if (previous.power != PowerSource.NONE) 15 else 0,
                        Channel.STEAM_CLUTCH to if (previous.power == PowerSource.STEAM) 15 else 0,
                        Channel.FUEL_FEED_CLUTCH to if (previous.fuel == FuelMode.FEED) 15 else 0,
                        Channel.FUEL_DRAIN_CLUTCH to if (previous.fuel == FuelMode.DRAIN) 15 else 0,
                    )
                var pumpsSettled = false
                for (step in outputSteps(previous, next)) {
                    if (step.channel == Channel.WAIT) {
                        assertTrue(step.value >= 2)
                        if (signals[Channel.FUEL_FEED_CLUTCH] == 0 && signals[Channel.FUEL_DRAIN_CLUTCH] == 0) {
                            pumpsSettled = true
                        }
                        continue
                    }
                    if ((step.channel == Channel.FUEL_FEED_CLUTCH || step.channel == Channel.FUEL_DRAIN_CLUTCH) && step.value == 15) {
                        assertEquals(0, signals[Channel.FUEL_FEED_CLUTCH])
                        assertEquals(0, signals[Channel.FUEL_DRAIN_CLUTCH])
                        assertTrue(pumpsSettled)
                        pumpsSettled = false
                    }
                    signals[step.channel] = step.value
                    assertFalse(signals[Channel.FUEL_FEED_CLUTCH] == 15 && signals[Channel.FUEL_DRAIN_CLUTCH] == 15)
                    assertFalse(
                        signals[Channel.BATTERY] == 15 && signals[Channel.BATTERY_CLUTCH] == 15 && signals[Channel.STEAM_CLUTCH] == 15,
                    )
                }
                assertEquals(if (next.power == PowerSource.BATTERY) 15 else 0, signals[Channel.BATTERY])
                assertEquals(if (next.power != PowerSource.NONE) 15 else 0, signals[Channel.BATTERY_CLUTCH])
                assertEquals(if (next.power == PowerSource.STEAM) 15 else 0, signals[Channel.STEAM_CLUTCH])
                assertEquals(if (next.fuel == FuelMode.FEED) 15 else 0, signals[Channel.FUEL_FEED_CLUTCH])
                assertEquals(if (next.fuel == FuelMode.DRAIN) 15 else 0, signals[Channel.FUEL_DRAIN_CLUTCH])
            }
        }
    }
}

private fun sample(
    value: BoilerSample,
    available: Boolean = value.available,
    waterSupply: Float = value.waterSupply,
    engineRpm: Float = value.engineRpm,
    fuelAmount: Int = value.fuelAmount,
    burnTicks: Int = value.burnTicks,
): BoilerSample =
    BoilerSample(
        available,
        waterSupply,
        value.waterLevel,
        value.heatLevel,
        value.level,
        engineRpm,
        fuelAmount,
        value.fuelCapacity,
        burnTicks,
    )

private fun <T> assertEquals(
    expected: T,
    actual: T,
) {
    check(expected == actual)
}

private fun assertTrue(value: Boolean) {
    check(value)
}

private fun assertFalse(value: Boolean) {
    check(!value)
}

fun main() {
    verifyPlatformRecords()
    val scenarios = ControllerScenarios()
    scenarios.explicitStartup()
    scenarios.residualFuelAndCooling()
    scenarios.restartDuringCooling()
    scenarios.remainingBurnTime()
    scenarios.telemetryFault()
    scenarios.startupAndDrainTimeouts()
    scenarios.lostSelfPowerAndLeakingFeed()
    scenarios.outputInterlocks()
    println("boiler controller ok")
}

private fun verifyPlatformRecords() {
    val info = FileInfo(FileKind.FILE, 4294967296L, 9L, true)
    val files = listOf(info)
    check(files[0] == info && files[0].sizeBytes == 4294967296L)
    val process: ProcessResult = ProcessResult.Exited(42)
    check(process is ProcessResult.Exited && process.code == 42)
    val failure: ProcessResult = ProcessResult.Failed(ProcessFailureReason.NOT_FOUND, "missing")
    check(failure is ProcessResult.Failed && failure.reason == ProcessFailureReason.NOT_FOUND && failure.diagnostic == "missing")
    val results =
        listOf<CompilationResult>(
            CompilationResult.Success(Path("/program")),
            CompilationResult.Failed(CompilationFailureReason.REJECTED, "error"),
        )
    val success = results[0]
    check(success is CompilationResult.Success && success.output.value == "/program")
    val failed = results[1]
    check(failed is CompilationResult.Failed && failed.diagnostics == "error")
    val events = listOf<TerminalEvent>(TerminalEvent.Text("typed"), TerminalEvent.SubmittedLine("line"))
    for (event in events) {
        when (event) {
            is TerminalEvent.Text -> check(event.text == "typed")
            is TerminalEvent.SubmittedLine -> check(event.text == "line")
            is TerminalEvent.Key -> error("unexpected key event")
        }
    }
    val boxed: Any = info
    check(boxed is FileInfo && boxed.revision == 9L)
    val optional: FileInfo? = info
    check(optional != null && optional.isExecutable)
}
