/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import ru.lazyhat.compukters.api.addon.ProgramAddonDispatch
import ru.lazyhat.compukters.api.addon.ProgramAddonRequest
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DisplayHostStateTest {
    @Test
    fun `named and adjacent handles write one exact display and reset clears it`() {
        val endpoint = FakeDisplayEndpoint()
        val host = DisplayHostState({ endpoint }, { DisplayResolution.Found(endpoint) })
        val adjacent = assertIs<HostResponse.IntSuccess>(host.call(0, VmValue.I32(0))).value
        val named = assertIs<HostResponse.IntSuccess>(host.call(1, VmValue.StringValue("screen"))).value
        assertEquals(adjacent, named)
        assertEquals(
            HostResponse.UnitSuccess,
            host.call(2, VmValue.I32(adjacent), VmValue.I32(1), VmValue.I32(2), VmValue.StringValue("Ready")),
        )
        assertEquals(" Ready", endpoint.buffer.rows()[2].trimEnd())
        host.reset()
        assertEquals(" ".repeat(DisplayBuffer.WIDTH), endpoint.buffer.rows()[2])
        assertIs<HostResponse.Failure>(host.call(3, VmValue.I32(adjacent)))
    }

    @Test
    fun `a replaced or disconnected display invalidates its acquired handle`() {
        val endpoint = FakeDisplayEndpoint()
        val host = DisplayHostState({ endpoint }, { DisplayResolution.Found(endpoint) })
        val handle = assertIs<HostResponse.IntSuccess>(host.call(0, VmValue.I32(0))).value
        endpoint.present = false
        val failure = assertIs<HostResponse.Failure>(host.call(3, VmValue.I32(handle)))
        assertEquals(HostFailureKind.INPUT_OUTPUT, failure.kind)
        assertEquals(" ".repeat(DisplayBuffer.WIDTH), endpoint.buffer.rows()[0])
    }

    @Test
    fun `malformed calls do not acquire a handle`() {
        val endpoint = FakeDisplayEndpoint()
        val host = DisplayHostState({ endpoint }, { DisplayResolution.Found(endpoint) })
        assertEquals(HostFailureKind.OTHER, assertIs<HostResponse.Failure>(host.call(0, VmValue.I32(0), VmValue.I32(1))).kind)
        assertEquals(1, assertIs<HostResponse.IntSuccess>(host.call(0, VmValue.I32(0))).value)
    }

    private fun DisplayHostState.call(
        operation: Int,
        vararg arguments: VmValue,
    ): HostResponse =
        assertIs<ProgramAddonDispatch.Completed>(
            dispatch(
                ProgramAddonRequest(
                    VmHostRequestIdentity(1, 1),
                    DisplayHostState.SCHEMA.identity,
                    operation,
                    arguments.toList(),
                ),
            ),
        ).response

    private class FakeDisplayEndpoint : DisplayEndpoint {
        override val identity = Any()
        override val buffer = DisplayBuffer()
        var present = true

        override fun valid(): Boolean = present
    }
}
