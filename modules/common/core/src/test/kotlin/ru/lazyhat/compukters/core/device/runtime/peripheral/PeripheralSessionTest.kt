/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.device.runtime.peripheral

import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class PeripheralSessionTest {
    private class Device(
        val stamp: String =
            java.util.UUID
                .randomUUID()
                .toString(),
    ) {
        var valid = true
    }

    private class World {
        val devices = linkedMapOf(3 to Device(), 7 to Device())
        var connected = true
        val speed =
            PeripheralContract<Int, Device>("test:speed", "test", "speed") { identity ->
                devices[identity.location]?.let { device ->
                    PeripheralEndpoint(device, device, device.stamp) {
                        connected && device.valid &&
                            devices[identity.location] === device
                    }
                }
            }
        val shared = PeripheralContract<Int, Device>("test:sensor", "test", "speed", speed.resolve)

        fun session(
            handles: Int = 4,
            entries: Int = 4,
        ) = PeripheralSession(
            listOf(speed, shared),
            discover = { devices.keys.map { PeripheralIdentity("test", it, "speed") } },
            side = { if (it == 0) listOf(PeripheralIdentity("test", 3, "speed")) else emptyList() },
            lookupName = { _, name ->
                when (name) {
                    "front" -> PeripheralIdentity("test", 3, "speed")
                    "wrong" -> PeripheralIdentity("test", 3, "other")
                    "ambiguous" -> throw PeripheralFailure(HostFailureKind.OTHER, "Ambiguous name")
                    else -> null
                }
            },
            maximumHandles = handles,
            maximumSnapshots = 1,
            maximumSnapshotEntries = entries,
            encodeLocation = {
                java.nio.ByteBuffer
                    .allocate(4)
                    .putInt(it)
                    .array()
            },
            decodeLocation = {
                require(it.size == 4)
                java.nio.ByteBuffer
                    .wrap(it)
                    .int
            },
        )
    }

    @Test
    fun `several contacts resolving to one logical device appear once in discovery`() {
        val world = World()
        world.devices[7] = world.devices.getValue(3)
        val session = world.session()
        val snapshot = session.openSnapshot(world.speed.id)
        assertEquals(1, session.snapshotSize(snapshot))
        assertSame(world.devices[3], session.endpoint(world.speed, session.snapshotGet(snapshot, 0)))
    }

    @Test
    fun `checkpoint restores exact handles discovery order and token high water marks`() {
        val world = World()
        val original = world.session()
        val snapshot = original.openSnapshot(world.speed.id)
        val handle = original.snapshotGet(snapshot, 0)
        val restored = world.session()
        restored.restoreCheckpoint(original.checkpoint())
        assertSame(world.devices[3], restored.endpoint(world.speed, handle))
        assertEquals(2, restored.snapshotSize(snapshot))
        assertEquals(handle, restored.snapshotGet(snapshot, 0))
        restored.close(world.speed, handle)
        assertNotEquals(handle, restored.at(world.speed.id, 0))
        restored.closeSnapshot(snapshot)
        assertNotEquals(snapshot, restored.openSnapshot(world.speed.id))
    }

    @Test
    fun `capture during owner detachment retains identity but never revives an observed stale endpoint`() {
        val world = World()
        val session = world.session()
        val snapshot = session.openSnapshot(world.speed.id)
        val handle = session.snapshotGet(snapshot, 0)
        world.connected = false
        val saved = session.checkpoint()
        world.connected = true
        val restored = world.session()
        restored.restoreCheckpoint(saved)
        assertSame(world.devices[3], restored.endpoint(world.speed, handle))
        world.connected = false
        assertFailsWith<PeripheralFailure> { session.endpoint(world.speed, handle) }
        val observedStale = session.checkpoint()
        world.connected = true
        val rejected = world.session()
        rejected.restoreCheckpoint(observedStale)
        assertFailsWith<PeripheralFailure> { rejected.snapshotGet(snapshot, 0) }
    }

    @Test
    fun `portable identity binds a freshly loaded instance but never a replacement at its address`() {
        val world = World()
        val original = world.session()
        val snapshot = original.openSnapshot(world.speed.id)
        val handle = original.snapshotGet(snapshot, 0)
        val bytes = original.checkpoint()
        val loaded = Device(requireNotNull(world.devices[3]).stamp)
        world.devices[3] = loaded
        val restored = world.session()
        restored.restoreCheckpoint(bytes)
        assertSame(loaded, restored.endpoint(world.speed, handle))
        world.devices[3] = Device()
        val replaced = world.session()
        replaced.restoreCheckpoint(bytes)
        assertEquals(HostFailureKind.INPUT_OUTPUT, assertFailsWith<PeripheralFailure> { replaced.endpoint(world.speed, handle) }.kind)
        assertFailsWith<PeripheralFailure> { replaced.snapshotGet(snapshot, 0) }
        assertNotEquals(handle, replaced.at(world.speed.id, 0))
    }

    @Test
    fun `truncated peripheral state is rejected before replacing live tables`() {
        val world = World()
        val session = world.session()
        val handle = session.at(world.speed.id, 0)
        val bytes = session.checkpoint()
        for (length in bytes.indices) {
            assertFailsWith<IllegalArgumentException> { session.restoreCheckpoint(bytes.copyOf(length)) }
            assertSame(world.devices[3], session.endpoint(world.speed, handle))
        }
        assertFailsWith<IllegalArgumentException> { session.restoreCheckpoint(bytes + byteArrayOf(0)) }
    }

    @Test
    fun `closing a handle invalidates aliases and allows a fresh acquisition`() {
        val world = World()
        val session = world.session()
        val old = session.at(world.speed.id, 0)
        assertFailsWith<PeripheralFailure> { session.close(world.shared, old) }
        assertSame(world.devices[3], session.endpoint(world.speed, old))
        session.close(world.speed, old)
        assertFailsWith<PeripheralFailure> { session.endpoint(world.speed, old) }
        val fresh = session.named(world.speed.id, "front")
        assertNotEquals(old, fresh)
        assertSame(world.devices[3], session.endpoint(world.speed, fresh))
    }

    @Test
    fun `snapshots preserve order and acquire only read device handles`() {
        val world = World()
        val session = world.session(handles = 1)
        val snapshot = session.openSnapshot(world.speed.id)
        assertEquals(2, session.snapshotSize(snapshot))
        val first = session.snapshotGet(snapshot, 0)
        assertSame(world.devices[3], session.endpoint(world.speed, first))
        assertEquals(first, session.at(world.speed.id, 0))
        assertEquals(first, session.named(world.speed.id, "front"))
        assertEquals(HostFailureKind.UNAVAILABLE, assertFailsWith<PeripheralFailure> { session.snapshotGet(snapshot, 1) }.kind)
        session.closeSnapshot(snapshot)
        assertFailsWith<PeripheralFailure> { session.snapshotSize(snapshot) }
    }

    @Test
    fun `snapshot and retained handles never bind replacement devices`() {
        val world = World()
        val session = world.session()
        val snapshot = session.openSnapshot(world.speed.id)
        val old = session.snapshotGet(snapshot, 0)
        world.devices[3] = Device()
        assertEquals(HostFailureKind.INPUT_OUTPUT, assertFailsWith<PeripheralFailure> { session.endpoint(world.speed, old) }.kind)
        assertFailsWith<PeripheralFailure> { session.snapshotGet(snapshot, 0) }
        val replacement = session.at(world.speed.id, 0)
        assertNotEquals(old, replacement)
        assertSame(world.devices[3], session.endpoint(world.speed, replacement))
        assertFailsWith<PeripheralFailure> { session.endpoint(world.speed, old) }
    }

    @Test
    fun `observed disconnect stays invalid after reconnect and reset never reuses tokens`() {
        val world = World()
        val session = world.session()
        val snapshot = session.openSnapshot(world.speed.id)
        val old = session.snapshotGet(snapshot, 0)
        world.connected = false
        assertFailsWith<PeripheralFailure> { session.endpoint(world.speed, old) }
        world.connected = true
        assertFailsWith<PeripheralFailure> { session.snapshotGet(snapshot, 0) }
        val next = session.at(world.speed.id, 0)
        assertNotEquals(old, next)
        session.reset()
        assertFailsWith<PeripheralFailure> { session.endpoint(world.speed, next) }
        assertFailsWith<PeripheralFailure> { session.snapshotSize(snapshot) }
        assertNotEquals(next, session.at(world.speed.id, 0))
        assertNotEquals(snapshot, session.openSnapshot(world.speed.id))
    }

    @Test
    fun `one logical device can expose several contracts without confusing handle types`() {
        val world = World()
        val session = world.session()
        val speed = session.at(world.speed.id, 0)
        val sensor = session.at(world.shared.id, 0)
        assertNotEquals(speed, sensor)
        assertSame(session.endpoint(world.speed, speed), session.endpoint(world.shared, sensor))
        assertEquals(HostFailureKind.OTHER, assertFailsWith<PeripheralFailure> { session.endpoint(world.shared, speed) }.kind)
        val forged = PeripheralContract<Int, Device>(world.speed.id, "test", "speed", world.speed.resolve)
        assertFailsWith<PeripheralFailure> { session.endpoint(forged, speed) }
    }

    @Test
    fun `optional queries distinguish absence from malformed wrong type and limit failures`() {
        val world = World()
        val session = world.session()
        assertEquals(0, session.at(world.speed.id, 1))
        assertEquals(0, session.named(world.speed.id, "missing"))
        assertEquals(HostFailureKind.OTHER, assertFailsWith<PeripheralFailure> { session.at(world.speed.id, 6) }.kind)
        assertEquals(HostFailureKind.OTHER, assertFailsWith<PeripheralFailure> { session.named(world.speed.id, "wrong") }.kind)
        assertFailsWith<PeripheralFailure> { session.named(world.speed.id, "ambiguous") }
        assertFailsWith<PeripheralFailure> { session.at("absent:contract", 0) }
        val snapshot = session.openSnapshot(world.speed.id)
        assertFailsWith<PeripheralFailure> { session.openSnapshot(world.speed.id) }
        assertFailsWith<PeripheralFailure> { session.snapshotGet(snapshot, -1) }
        session.closeSnapshot(snapshot)
        assertEquals(
            HostFailureKind.UNAVAILABLE,
            assertFailsWith<PeripheralFailure> {
                world.session(entries = 1).openSnapshot(world.speed.id)
            }.kind,
        )
    }
}
