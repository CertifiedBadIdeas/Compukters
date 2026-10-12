/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.device.runtime.peripheral

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PeripheralHandleLeaseTest {
    private class Device : PeripheralHandleLease {
        var valid = true
        var acquisitions = 0
        var releases = 0
        var busy = false

        override fun acquire() {
            if (busy) throw PeripheralFailure(ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind.UNAVAILABLE, "Busy")
            acquisitions += 1
        }

        override fun release() {
            releases += 1
        }
    }

    private class Fixture {
        val device = Device()
        val contract =
            PeripheralContract<Int, Device>("test:lease", "test", "lease") {
                PeripheralEndpoint(device, device, "stamp") { device.valid }
            }
        val session =
            PeripheralSession(
                listOf(contract),
                discover = { listOf(PeripheralIdentity("test", 1, "lease")) },
                side = { listOf(PeripheralIdentity("test", 1, "lease")) },
                lookupName = { _, _ -> PeripheralIdentity("test", 1, "lease") },
            )
    }

    @Test
    fun `snapshot inspection never acquires a device and duplicate handles acquire only once`() {
        val f = Fixture()
        val snapshot = f.session.openSnapshot(f.contract.id)
        assertEquals(1, f.session.snapshotSize(snapshot))
        assertEquals(0, f.device.acquisitions)
        val handle = f.session.snapshotGet(snapshot, 0)
        assertEquals(handle, f.session.named(f.contract.id, "device"))
        assertEquals(1, f.device.acquisitions)
        f.session.closeSnapshot(snapshot)
        assertEquals(0, f.device.releases)
        f.session.close(f.contract, handle)
        assertEquals(1, f.device.releases)
        f.session.reset()
        assertEquals(1, f.device.releases)
    }

    @Test
    fun `passive expiry releases exactly once and stale handle stays invalid`() {
        val f = Fixture()
        val handle = f.session.at(f.contract.id, 0)
        f.device.valid = false
        f.session.expire()
        f.session.expire()
        assertEquals(1, f.device.releases)
        f.device.valid = true
        assertFailsWith<PeripheralFailure> { f.session.endpoint(f.contract, handle) }
        f.session.reset()
        assertEquals(1, f.device.releases)
    }

    @Test
    fun `failed acquisition does not retain a handle or release another owners lease`() {
        val f = Fixture()
        f.device.busy = true
        assertFailsWith<PeripheralFailure> { f.session.named(f.contract.id, "device") }
        f.session.reset()
        assertEquals(0, f.device.acquisitions)
        assertEquals(0, f.device.releases)
        f.device.busy = false
        f.session.named(f.contract.id, "device")
        f.session.reset()
        assertEquals(1, f.device.acquisitions)
        assertEquals(1, f.device.releases)
    }

    @Test
    fun `invalid device access releases ownership without waiting for polling`() {
        val f = Fixture()
        val handle = f.session.at(f.contract.id, 0)
        f.device.valid = false
        assertFailsWith<PeripheralFailure> { f.session.endpoint(f.contract, handle) }
        assertEquals(1, f.device.releases)
        f.session.reset()
        assertEquals(1, f.device.releases)
    }
}
