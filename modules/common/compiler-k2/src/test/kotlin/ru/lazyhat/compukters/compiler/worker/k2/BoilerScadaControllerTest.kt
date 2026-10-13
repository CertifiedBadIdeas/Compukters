/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.worker.k2

import boiler.control.BoilerControl
import boiler.control.BoilerSample
import boiler.control.Channel
import boiler.control.FuelDirection
import boiler.control.Outputs
import boiler.control.Phase
import boiler.control.PowerSource
import boiler.control.outputSteps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoilerScadaControllerTest {
    private val cold = BoilerSample(true, fuelCapacity = 4000)
    private val hot = BoilerSample(true, 20f, 1, 1, 1, 16f, 1200, 4000)
    private val off = Outputs(PowerSource.NONE, FuelDirection.OFF)

    @Test
    fun `startup is explicit and requires consecutive independent engine readings`() {
        val control = BoilerControl(stableSamples = 3)
        repeat(10) { control.observe(hot) }
        assertEquals(off, control.outputs)
        control.start(cold.copy(available = false))
        assertEquals(Phase.IDLE, control.phase)
        control.start(cold)
        assertEquals(Outputs(PowerSource.BATTERY, FuelDirection.FEED), control.outputs)
        repeat(2) { control.observe(hot) }
        control.observe(hot.copy(engineRpm = 0f))
        repeat(2) { control.observe(hot) }
        assertEquals(Phase.STARTING, control.phase)
        control.observe(hot)
        assertEquals(Outputs(PowerSource.STEAM, FuelDirection.FEED), control.outputs)
    }

    @Test
    fun `stop drains even a small residual and waits for burning fuel to cool`() {
        val control = BoilerControl(stableSamples = 3)
        control.start(cold)
        control.stop(hot)
        assertEquals(Outputs(PowerSource.BATTERY, FuelDirection.DRAIN), control.outputs)
        repeat(5) { control.observe(hot.copy(fuelAmount = 1)) }
        assertEquals(Phase.DRAINING, control.phase)
        repeat(2) { control.observe(hot.copy(fuelAmount = 0)) }
        control.observe(hot.copy(fuelAmount = 1))
        repeat(3) { control.observe(hot.copy(fuelAmount = 0)) }
        assertEquals(Phase.COOLING, control.phase)
        assertEquals(off, control.outputs)
        control.start(cold)
        assertEquals(Phase.COOLING, control.phase)
        control.observe(cold)
        assertEquals(Phase.IDLE, control.phase)
    }

    @Test
    fun `cooldown includes fuel already converted into burning time`() {
        val control = BoilerControl(stableSamples = 1)
        control.stop(cold)
        control.observe(cold.copy(burnTicks = 100))
        assertEquals(Phase.COOLING, control.phase)
        control.observe(cold.copy(burnTicks = 1))
        assertEquals(Phase.COOLING, control.phase)
        control.observe(cold)
        assertEquals(Phase.IDLE, control.phase)
    }

    @Test
    fun `telemetry loss latches a fault and recovery cannot restart`() {
        val control = BoilerControl()
        control.start(cold)
        control.observe(cold.copy(available = false))
        assertEquals(Phase.FAULT, control.phase)
        assertEquals(off, control.outputs)
        control.observe(hot)
        control.start(hot)
        control.reset(hot)
        assertEquals(Phase.FAULT, control.phase)
        control.stop(hot)
        assertEquals(Phase.DRAINING, control.phase)
    }

    @Test
    fun `failed startup and blocked fuel return time out without leaving power on`() {
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

    @Test
    fun `lost self power returns to battery for drain and leaking feed cannot end shutdown`() {
        val control = BoilerControl(stableSamples = 2)
        control.start(cold)
        repeat(2) { control.observe(hot) }
        repeat(3) { control.observe(hot.copy(waterSupply = 0f)) }
        assertEquals(Phase.DRAINING, control.phase)
        assertEquals(PowerSource.BATTERY, control.outputs.power)
        repeat(2) { control.observe(hot.copy(fuelAmount = 0)) }
        control.observe(hot)
        assertEquals(Phase.DRAINING, control.phase)
        repeat(2) { control.observe(cold) }
        control.observe(cold)
        assertEquals(Phase.FAULT, control.phase)
        assertEquals("SELF POWER LOST", control.alarm)
    }

    @Test
    fun `every output transition interlocks powered sources and pump reversal`() {
        val states =
            listOf(
                off,
                Outputs(PowerSource.BATTERY, FuelDirection.FEED),
                Outputs(PowerSource.STEAM, FuelDirection.FEED),
                Outputs(PowerSource.BATTERY, FuelDirection.DRAIN),
            )
        for (previous in states) {
            for (next in states) {
                val signals =
                    mutableMapOf(
                        Channel.BATTERY to if (previous.power == PowerSource.BATTERY) 15 else 0,
                        Channel.BATTERY_CLUTCH to if (previous.power != PowerSource.NONE) 15 else 0,
                        Channel.STEAM_CLUTCH to if (previous.power == PowerSource.STEAM) 15 else 0,
                        Channel.FUEL_CLUTCH to if (previous.fuel != FuelDirection.OFF) 15 else 0,
                        Channel.FUEL_REVERSE to if (previous.fuel == FuelDirection.DRAIN) 15 else 0,
                    )
                var waited = false
                for (step in outputSteps(previous, next)) {
                    if (step.channel == Channel.WAIT) {
                        assertTrue(step.value >= 2)
                        waited = true
                        continue
                    }
                    if (step.channel == Channel.FUEL_REVERSE && signals[step.channel] != step.value) {
                        assertEquals(0, signals[Channel.FUEL_CLUTCH])
                        assertTrue(waited)
                    }
                    signals[step.channel] = step.value
                    assertFalse(
                        signals[Channel.BATTERY] == 15 && signals[Channel.BATTERY_CLUTCH] == 15 && signals[Channel.STEAM_CLUTCH] == 15,
                    )
                }
                assertEquals(if (next.power == PowerSource.BATTERY) 15 else 0, signals[Channel.BATTERY])
                assertEquals(if (next.power != PowerSource.NONE) 15 else 0, signals[Channel.BATTERY_CLUTCH])
                assertEquals(if (next.power == PowerSource.STEAM) 15 else 0, signals[Channel.STEAM_CLUTCH])
                assertEquals(if (next.fuel == FuelDirection.OFF) 0 else 15, signals[Channel.FUEL_CLUTCH])
            }
        }
    }
}
