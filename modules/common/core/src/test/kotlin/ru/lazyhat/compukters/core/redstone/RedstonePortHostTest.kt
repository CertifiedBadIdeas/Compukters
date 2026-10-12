/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.redstone

import ru.lazyhat.compukters.api.addon.ProgramAddonDispatch
import ru.lazyhat.compukters.api.addon.ProgramAddonRequest
import ru.lazyhat.compukters.core.device.runtime.peripheral.PeripheralFailure
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RedstonePortHostTest {
    private class Fixture {
        val state = RedstonePortState()
        var connected = true
        val endpoint =
            object : RedstonePortEndpoint {
                override val signals = state

                override fun acquire() = state.acquire(this) { connected }

                override fun release() = state.release(this)
            }.also { it.acquire() }
        val host =
            RedstonePortHost(
                resolve = {
                    state.expire()
                    if (it != 1 || !connected || !state.owns(endpoint)) throw PeripheralFailure(HostFailureKind.INPUT_OUTPUT, "Expired")
                    endpoint
                },
                release = { endpoint.release() },
            )
        private var requestId = 0L

        fun call(
            operation: Int,
            vararg values: Int,
        ): ProgramAddonDispatch =
            host.dispatch(
                ProgramAddonRequest(
                    VmHostRequestIdentity(1, ++requestId),
                    RedstonePortHost.SCHEMA.identity,
                    operation,
                    values.map(VmValue::I32),
                ),
            )
    }

    @Test
    fun `broadcast operation writes all faces and rejects malformed levels`() {
        val f = Fixture()
        assertEquals(ProgramAddonDispatch.Completed(HostResponse.UnitSuccess), f.call(5, 1, 23))
        assertEquals(List(6) { 23 }, (0..5).map(f.state::output))
        val failed = assertIs<ProgramAddonDispatch.Completed>(f.call(5, 1, 32)).response
        assertIs<HostResponse.Failure>(failed)
        assertEquals(List(6) { 23 }, (0..5).map(f.state::output))
        assertEquals(ProgramAddonDispatch.Completed(HostResponse.UnitSuccess), f.call(4, 1))
        assertEquals(List(6) { 0 }, (0..5).map(f.state::output))
    }

    @Test
    fun `change waits ignore past and other-face changes and return zero as a valid transition`() {
        val f = Fixture()
        f.state.sample(intArrayOf(15, 0, 0, 0, 0, 0))
        assertEquals(ProgramAddonDispatch.Pending, f.call(2, 1, 0))
        assertTrue(f.host.poll(4).isEmpty())
        f.state.sample(intArrayOf(15, 7, 0, 0, 0, 0))
        assertTrue(f.host.poll(4).isEmpty())
        f.state.sample(IntArray(6))
        assertEquals(
            HostResponse.IntSuccess(0),
            f.host
                .poll(4)
                .single()
                .response,
        )
        assertTrue(f.host.poll(4).isEmpty())
    }

    @Test
    fun `level waits complete immediately or asynchronously with bounded poll completions`() {
        val f = Fixture()
        assertEquals(ProgramAddonDispatch.Completed(HostResponse.IntSuccess(0)), f.call(3, 1, 0, 1, 0))
        assertEquals(ProgramAddonDispatch.Pending, f.call(3, 1, 0, 1, 7))
        assertEquals(ProgramAddonDispatch.Pending, f.call(3, 1, 0, 2, 5))
        f.state.sample(intArrayOf(7, 0, 0, 0, 0, 0))
        assertEquals(1, f.host.poll(1).size)
        assertEquals(
            HostResponse.IntSuccess(7),
            f.host
                .poll(1)
                .single()
                .response,
        )
    }

    @Test
    fun `disconnect fails wait and clears output without replaying a write`() {
        val f = Fixture()
        assertEquals(ProgramAddonDispatch.Completed(HostResponse.UnitSuccess), f.call(1, 1, 0, 31))
        f.call(2, 1, 1)
        f.connected = false
        val failed =
            assertIs<HostResponse.Failure>(
                f.host
                    .poll(4)
                    .single()
                    .response,
            )
        assertEquals(HostFailureKind.INPUT_OUTPUT, failed.kind)
        assertEquals(0, f.state.output(0))
    }

    @Test
    fun `saved waits resume as interruption without rebinding or restoring outputs`() {
        val f = Fixture()
        f.call(2, 1, 0)
        val saved = f.host.checkpoint()
        f.host.reset()
        f.endpoint.release()
        f.host.restoreCheckpoint(saved)
        val failed =
            assertIs<HostResponse.Failure>(
                f.host
                    .poll(1)
                    .single()
                    .response,
            )
        assertEquals(HostFailureKind.INPUT_OUTPUT, failed.kind)
        assertEquals(0, f.state.output(0))
    }

    @Test
    fun `malformed boundaries fail without changing physical output`() {
        val f = Fixture()
        for (args in listOf(intArrayOf(1, 6, 15), intArrayOf(1, 0, 32), intArrayOf(1, 0))) {
            val response = assertIs<ProgramAddonDispatch.Completed>(f.call(1, *args)).response
            assertIs<HostResponse.Failure>(response)
            assertEquals(0, f.state.output(0))
        }
    }
}
