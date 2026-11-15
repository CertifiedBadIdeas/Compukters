/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.program

import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetDetachedState
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetEvent
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetSessionOwner
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetTimer
import ru.lazyhat.compukters.lang.runtime.vm.VmBridgeException
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgramFleetCheckpointTest {
    @Test
    fun `checkpoint uses detached scopes even when lifecycle observations never reached the host`() {
        val h = Harness()
        val timer = ExperimentalFleetTimer(VmHostRequestIdentity(1, 2), 7, 8, 4)
        val detached = ExperimentalFleetEvent.Detached(3, listOf(timer), ExperimentalFleetDetachedState(listOf(0, 7), 9))
        assertTrue(h.host.hibernateFromFleet(h.fleet, detached, 10, byteArrayOf(1, 2)))
        val checkpoint = ProgramHostCheckpoint.decode(checkNotNull(h.saved))
        assertEquals(listOf(0L, 7L), checkpoint.programScopes)
        assertEquals(9, checkpoint.confirmedRedstoneOutput)
        assertEquals(7L, checkpoint.timers.single().programId)
        assertEquals(4L, checkpoint.timers.single().remainingTicks)
        assertContentEquals(byteArrayOf(1, 2), checkpoint.addonState)
        assertEquals(1, h.recoveries)
        assertEquals(1, h.closes)
    }

    @Test
    fun `missing metadata and mismatched ownership do not recover or close the native machine`() {
        val h = Harness()
        assertFailsWith<IllegalArgumentException> {
            h.host.hibernateFromFleet(h.fleet, ExperimentalFleetEvent.Detached(3), 10, byteArrayOf())
        }
        val state = ExperimentalFleetDetachedState(listOf(0), 0)
        assertFailsWith<IllegalStateException> {
            h.host.hibernateFromFleet(h.fleet, ExperimentalFleetEvent.Detached(4, state = state), 10, byteArrayOf())
        }
        assertEquals(0, h.recoveries)
        assertEquals(0, h.closes)
        assertTrue(h.host.hibernateFromFleet(h.fleet, ExperimentalFleetEvent.Detached(3, state = state), 10, byteArrayOf()))
    }

    @Test
    fun `failed checkpoint retains recovered ownership for retry without recovering twice`() {
        val h = Harness()
        h.failCapture = true
        val detached = ExperimentalFleetEvent.Detached(3, state = ExperimentalFleetDetachedState(listOf(0), 0))
        assertFailsWith<VmBridgeException> { h.host.hibernateFromFleet(h.fleet, detached, 10, byteArrayOf()) }
        assertEquals(1, h.recoveries)
        assertEquals(0, h.closes)
        assertFalse(h.host.state == ProgramRuntimeState.Closed)
        h.failCapture = false
        assertTrue(h.host.hibernate(10, byteArrayOf()))
        assertEquals(1, h.recoveries)
        assertEquals(1, h.closes)
    }

    private class Harness {
        var saved: ByteArray? = null
        var recoveries = 0
        var closes = 0
        var failCapture = false
        val fleet =
            Proxy.newProxyInstance(
                ExperimentalFleetSessionOwner::class.java.classLoader,
                arrayOf(ExperimentalFleetSessionOwner::class.java),
            ) { _, method, _ -> error("unexpected fleet call: ${method.name}") } as ExperimentalFleetSessionOwner
        private val session =
            Proxy.newProxyInstance(
                ProgramVmSession::class.java.classLoader,
                arrayOf(ProgramVmSession::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "transferToFleet" -> {
                        3L
                    }

                    "recoverFromFleet" -> {
                        recoveries++
                        null
                    }

                    "filesystemGeneration" -> {
                        5L
                    }

                    "confirmRedstoneOutput", "submitRedstoneInput" -> {
                        null
                    }

                    "checkpoint" -> {
                        if (failCapture) throw VmBridgeException("store busy")
                        saved = args[0] as ByteArray
                        null
                    }

                    "close" -> {
                        closes++
                        null
                    }

                    else -> {
                        error("unexpected session call: ${method.name}")
                    }
                }
            } as ProgramVmSession
        val host =
            ProgramRuntimeHost(ProgramVmSessionFactory { session }).also {
                assertEquals(ProgramStartResult.Started, it.start(byteArrayOf()))
                // start() releases an empty session; only the active wrapper is counted here.
                closes = 0
                it.transferToFleet(fleet, 0)
            }
    }
}
