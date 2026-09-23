/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.integration.create

import com.simibubi.create.content.fluids.tank.BoilerData
import create.CreateAddonContract
import ru.lazyhat.compukters.api.addon.ProgramAddonDispatch
import ru.lazyhat.compukters.api.addon.ProgramAddonHost
import ru.lazyhat.compukters.api.addon.ProgramAddonRequest
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class BoilerHostStateTest {
    @Test
    fun `readings follow Create water, heat, size, and passive limits`() {
        val boiler = BoilerData()
        boiler.waterSupply = 19.1f
        boiler.activeHeat = 8

        assertEquals(2, boiler.getMaxHeatLevelForWaterSupply())
        assertEquals(5, boiler.getMaxHeatLevelForBoilerSize(20))
        assertEquals(2, activeBoilerLevel(boiler, 20))
        assertEquals(8, displayHeatLevel(boiler))
        assertEquals(false, passiveBoilerStatus(boiler, 20))

        boiler.waterSupply = 200f
        assertEquals(5, activeBoilerLevel(boiler, 20))
        boiler.activeHeat = 0
        boiler.passiveHeat = true
        assertEquals(0, activeBoilerLevel(boiler, 20))
        assertEquals(1, displayHeatLevel(boiler))
        assertEquals(true, passiveBoilerStatus(boiler, 20))
        boiler.waterSupply = 0f
        assertEquals(false, passiveBoilerStatus(boiler, 20))
    }

    @Test
    fun `side and named boiler handles report Create readings`() {
        val boiler = FakeBoiler(20f, 2, 8, 2, false)
        val host =
            CreateAddonContract.host(
                CreateHostState(
                    resolveSide = { side, kind -> boiler.takeIf { side == 2 && kind == PeripheralKind.BOILER } },
                    resolveName = { name, kind ->
                        if (name == "main_boiler" && kind == PeripheralKind.BOILER) {
                            CreateNamedResolution.Found(boiler)
                        } else {
                            CreateNamedResolution.Failed(HostFailureKind.UNAVAILABLE, "missing")
                        }
                    },
                ),
            )

        val handle = host.call(23, VmValue.I32(2)).intValue()
        assertEquals(handle, host.call(24, VmValue.StringValue("main_boiler")).intValue())
        assertEquals(HostResponse.FloatSuccess(20f), host.call(28, VmValue.I32(handle)))
        assertEquals(HostResponse.IntSuccess(2), host.call(29, VmValue.I32(handle)))
        assertEquals(HostResponse.IntSuccess(8), host.call(25, VmValue.I32(handle)))
        assertEquals(HostResponse.IntSuccess(2), host.call(27, VmValue.I32(handle)))
        assertEquals(HostResponse.BoolSuccess(false), host.call(26, VmValue.I32(handle)))

        boiler.water = 10f
        boiler.waterLevel = 1
        boiler.heat = 1
        boiler.level = 0
        boiler.passive = true
        assertEquals(HostResponse.FloatSuccess(10f), host.call(28, VmValue.I32(handle)))
        assertEquals(HostResponse.IntSuccess(1), host.call(29, VmValue.I32(handle)))
        assertEquals(HostResponse.IntSuccess(0), host.call(27, VmValue.I32(handle)))
        assertEquals(HostResponse.IntSuccess(1), host.call(25, VmValue.I32(handle)))
        assertEquals(HostResponse.BoolSuccess(true), host.call(26, VmValue.I32(handle)))
    }

    @Test
    fun `boiler handle fails after reconfiguration and reacquisition gets a new handle`() {
        val sameTankIdentity = Any()
        val old = FakeBoiler(20f, 2, 10, 2, false, sameTankIdentity)
        var current = old
        val host =
            CreateAddonContract.host(
                CreateHostState { _, kind -> current.takeIf { kind == PeripheralKind.BOILER } },
            )
        val oldHandle = host.call(23, VmValue.I32(0)).intValue()
        old.connected = false
        current = FakeBoiler(30f, 3, 12, 3, false, sameTankIdentity)

        val newHandle = host.call(23, VmValue.I32(0)).intValue()
        assertNotEquals(oldHandle, newHandle)
        assertEquals(HostFailureKind.UNAVAILABLE, assertIs<HostResponse.Failure>(host.call(28, VmValue.I32(oldHandle))).kind)
        assertEquals(HostResponse.FloatSuccess(30f), host.call(28, VmValue.I32(newHandle)))
    }

    @Test
    fun `missing and stale boilers fail without returning readings`() {
        val boiler = FakeBoiler(10f, 1, 6, 1, false)
        var present = false
        val host =
            CreateAddonContract.host(
                CreateHostState { _, kind -> boiler.takeIf { present && kind == PeripheralKind.BOILER } },
            )
        assertEquals(HostFailureKind.UNAVAILABLE, assertIs<HostResponse.Failure>(host.call(23, VmValue.I32(0))).kind)

        present = true
        val handle = host.call(23, VmValue.I32(0)).intValue()
        boiler.connected = false
        val failure = assertIs<HostResponse.Failure>(host.call(27, VmValue.I32(handle)))
        assertEquals(HostFailureKind.INPUT_OUTPUT, failure.kind)
        assertEquals("Create boiler was removed, reconfigured, disconnected, or unloaded", failure.detail)
    }

    private fun ProgramAddonHost.call(
        operation: Int,
        vararg values: VmValue,
    ): HostResponse =
        assertIs<ProgramAddonDispatch.Completed>(
            dispatch(ProgramAddonRequest(VmHostRequestIdentity(1, nextRequestId++), CREATE, operation, values.toList())),
        ).response

    private fun HostResponse.intValue(): Int = assertIs<HostResponse.IntSuccess>(this).value

    private class FakeBoiler(
        var water: Float,
        var waterLevel: Int,
        var heat: Int,
        var level: Int,
        var passive: Boolean,
        override val identity: Any = Any(),
    ) : BoilerAccess {
        var connected = true

        override fun valid(): Boolean = connected

        override fun waterSupply(): Float = water

        override fun waterLevel(): Int = waterLevel

        override fun heatLevel(): Int = heat

        override fun level(): Int = level

        override fun isPassive(): Boolean = passive
    }

    private companion object {
        val CREATE = CapabilityIdentity("create", "create", 1, 0)
        var nextRequestId = 20_000L
    }
}
