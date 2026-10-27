/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.input

import ru.lazyhat.compukters.api.addon.ProgramAddonDispatch
import ru.lazyhat.compukters.api.addon.ProgramAddonRequest
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class KeyboardProgramHostTest {
    private class Port : KeyboardEndpoint {
        var owner: Any? = null
        var state: KeyboardInputState? = null

        override fun acquire(
            owner: Any,
            mask: Long,
        ) {
            if (this.owner != null && this.owner !== owner) throw KeyboardUnavailable("busy")
            this.owner = owner
            state = KeyboardInputState(mask)
        }

        override fun snapshot(owner: Any): KeyboardFrame {
            check(owner === this.owner)
            return checkNotNull(state).poll(10)
        }

        override fun release(owner: Any) {
            if (this.owner === owner) {
                this.owner = null
                state = null
            }
        }
    }

    @Test fun `capture is exclusive and closing or stopping releases ownership without reviving handles`() {
        val port = Port()
        val first = KeyboardProgramHost(port)
        val second = KeyboardProgramHost(port)
        val handle = assertIs<HostResponse.IntSuccess>(call(first, 0, VmValue.I64(3))).value
        assertIs<HostResponse.Failure>(call(second, 0, VmValue.I64(3)))
        assertEquals(HostResponse.UnitSuccess, call(first, 2, VmValue.I32(handle)))
        assertIs<HostResponse.Failure>(call(first, 1, VmValue.I32(handle)))
        assertIs<HostResponse.IntSuccess>(call(second, 0, VmValue.I64(3)))
        second.reset()
        val replacement = assertIs<HostResponse.IntSuccess>(call(first, 0, VmValue.I64(3))).value
        assertFalse(handle == replacement)
        first.reset()
        assertIs<HostResponse.IntSuccess>(call(second, 0, VmValue.I64(1)))
    }

    @Test fun `checkpoint restores the request and handle but never player consent or physical keys`() {
        val port = Port()
        val first = KeyboardProgramHost(port)
        val handle = assertIs<HostResponse.IntSuccess>(call(first, 0, VmValue.I64(Long.MIN_VALUE))).value
        port.state!!.connect(10)
        port.state!!.update(0, Long.MIN_VALUE, 10)
        val checkpoint = first.checkpoint()
        first.close()
        val restored = KeyboardProgramHost(port)
        restored.restoreCheckpoint(checkpoint)
        val bytes = assertIs<HostResponse.ByteArraySuccess>(call(restored, 1, VmValue.I32(handle))).value
        assertEquals(34, bytes.size)
        assertEquals(1, bytes[0].toInt())
        assertEquals(0, bytes[1].toInt())
        val wire = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(10L, wire.getLong(2))
        for (offset in listOf(10, 18, 26)) assertEquals(0L, wire.getLong(offset))
    }

    @Test fun `invalid masks argument types and operations fail without taking ownership`() {
        val port = Port()
        val host = KeyboardProgramHost(port)
        assertIs<HostResponse.Failure>(call(host, 0, VmValue.I64(0)))
        assertIs<HostResponse.Failure>(call(host, 0, VmValue.I32(1)))
        assertIs<HostResponse.Failure>(call(host, 3, VmValue.I64(1)))
        assertIs<HostResponse.IntSuccess>(call(host, 0, VmValue.I64(1)))
    }

    @Test fun `all stable key bits have unique physical mappings including the signed high bit`() {
        assertEquals(64, KeyboardKeys.codes.size)
        assertEquals(64, KeyboardKeys.codes.distinct().size)
        assertEquals(22, KeyboardKeys.index(87)) // W
        assertEquals(40, KeyboardKeys.index(344)) // right Shift shares the Shift bit
        assertEquals(63, KeyboardKeys.index(267)) // Page Down
        assertEquals(-1, KeyboardKeys.index(256)) // Escape is never capturable
    }

    private fun call(
        host: KeyboardProgramHost,
        operation: Int,
        value: VmValue,
    ): HostResponse =
        assertIs<ProgramAddonDispatch.Completed>(
            host.dispatch(ProgramAddonRequest(VmHostRequestIdentity(1, 1), KeyboardProgramHost.SCHEMA.identity, operation, listOf(value))),
        ).response
}
