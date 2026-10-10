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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DisplayHostStateTest {
    @Test
    fun `named and adjacent handles write one exact display and reset preserves it`() {
        val endpoint = FakeDisplayEndpoint()
        val host = DisplayHostState({ endpoint }, { DisplayResolution.Found(endpoint) })
        val adjacent = assertIs<HostResponse.IntSuccess>(host.call(0, VmValue.I32(0))).value
        val named = assertIs<HostResponse.IntSuccess>(host.call(1, VmValue.StringValue("screen"))).value
        assertEquals(adjacent, named)
        assertEquals(
            HostResponse.UnitSuccess,
            host.call(9, VmValue.I32(adjacent), VmValue.I32(1), VmValue.I32(2), VmValue.I32(0x123456)),
        )
        assertEquals(0x123456, endpoint.canvas.pixelAt(1, 2))
        host.reset()
        assertEquals(0x123456, endpoint.canvas.pixelAt(1, 2))
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
        assertEquals(0, endpoint.canvas.pixelAt(0, 0))
    }

    @Test
    fun `malformed calls do not acquire a handle`() {
        val endpoint = FakeDisplayEndpoint()
        val host = DisplayHostState({ endpoint }, { DisplayResolution.Found(endpoint) })
        assertEquals(HostFailureKind.OTHER, assertIs<HostResponse.Failure>(host.call(0, VmValue.I32(0), VmValue.I32(1))).kind)
        assertEquals(1, assertIs<HostResponse.IntSuccess>(host.call(0, VmValue.I32(0))).value)
    }

    @Test
    fun `frames stay private until commit and reset discards drafts`() {
        val endpoint = FakeDisplayEndpoint()
        val host = DisplayHostState({ endpoint }, { DisplayResolution.Found(endpoint) })
        val handle = assertIs<HostResponse.IntSuccess>(host.call(0, VmValue.I32(0))).value
        assertEquals(HostResponse.UnitSuccess, host.call(14, VmValue.I32(handle)))
        assertEquals(HostResponse.UnitSuccess, host.call(9, VmValue.I32(handle), VmValue.I32(0), VmValue.I32(0), VmValue.I32(123)))
        assertEquals(0, endpoint.canvas.pixelAt(0, 0))
        assertEquals(HostResponse.UnitSuccess, host.call(15, VmValue.I32(handle)))
        assertEquals(123, endpoint.canvas.pixelAt(0, 0))
        host.call(14, VmValue.I32(handle))
        host.call(8, VmValue.I32(handle), VmValue.I32(456))
        host.reset()
        assertEquals(123, endpoint.canvas.pixelAt(0, 0))
    }

    @Test
    fun `malformed RGB image and mode requests leave pixels intact`() {
        val endpoint = FakeDisplayEndpoint()
        val host = DisplayHostState({ endpoint }, { DisplayResolution.Found(endpoint) })
        val handle = assertIs<HostResponse.IntSuccess>(host.call(0, VmValue.I32(0))).value
        assertIs<HostResponse.Failure>(host.call(7, VmValue.I32(handle), VmValue.I32(4)))
        assertIs<HostResponse.Failure>(host.call(13, VmValue.I32(handle), VmValue.I32(0), VmValue.I32(0), VmValue.StringValue("zz0000")))
        assertEquals(0, endpoint.canvas.pixelAt(0, 0))
        assertEquals(2, endpoint.canvas.mode)
    }

    @Test
    fun `cooperative tasks cannot draw into or commit another tasks frame`() {
        val endpoint = FakeDisplayEndpoint()
        val host = DisplayHostState({ endpoint }, { DisplayResolution.Found(endpoint) })
        val handle = assertIs<HostResponse.IntSuccess>(host.call(0, VmValue.I32(0))).value
        assertEquals(HostResponse.UnitSuccess, host.call(14, VmValue.I32(handle)))

        fun other(
            operation: Int,
            vararg arguments: VmValue,
        ): HostResponse =
            assertIs<ProgramAddonDispatch.Completed>(
                host.dispatch(
                    ProgramAddonRequest(
                        VmHostRequestIdentity(2, 2),
                        DisplayHostState.SCHEMA.identity,
                        operation,
                        arguments.toList(),
                    ),
                ),
            ).response
        assertIs<HostResponse.Failure>(other(9, VmValue.I32(handle), VmValue.I32(0), VmValue.I32(0), VmValue.I32(123)))
        assertIs<HostResponse.Failure>(other(15, VmValue.I32(handle)))
        assertEquals(HostResponse.UnitSuccess, host.call(16, VmValue.I32(handle)))
        assertEquals(0, endpoint.canvas.pixelAt(0, 0))
    }

    @Test
    fun `legacy text overwrites its cells including spaces without changing other pixels`() {
        val endpoint = FakeDisplayEndpoint()
        val host = DisplayHostState({ endpoint }, { DisplayResolution.Found(endpoint) })
        val handle = assertIs<HostResponse.IntSuccess>(host.call(0, VmValue.I32(0))).value
        assertEquals(
            HostResponse.UnitSuccess,
            host.call(2, VmValue.I32(handle), VmValue.I32(0), VmValue.I32(0), VmValue.StringValue("WWW")),
        )
        assertEquals(3, endpoint.canvas.mode)
        assertTrue(endpoint.canvas.encodeRgb().any { it != 0.toByte() })
        host.call(9, VmValue.I32(handle), VmValue.I32(100), VmValue.I32(100), VmValue.I32(123))
        assertEquals(
            HostResponse.UnitSuccess,
            host.call(2, VmValue.I32(handle), VmValue.I32(0), VmValue.I32(0), VmValue.StringValue("   ")),
        )
        assertFalse((0 until 12).any { y -> (0 until 18).any { x -> endpoint.canvas.pixelAt(x, y) != 0 } })
        assertEquals(123, endpoint.canvas.pixelAt(100, 100))
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
        override val canvas =
            ru.lazyhat.compukters.core.display
                .DisplayCanvas(1, 1)
        var present = true

        override fun valid(): Boolean = present
    }
}
