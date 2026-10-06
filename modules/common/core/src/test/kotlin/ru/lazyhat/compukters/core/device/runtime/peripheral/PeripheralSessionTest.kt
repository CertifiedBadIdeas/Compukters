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
    private class Device {
        var valid = true
    }

    private class World {
        val devices = linkedMapOf(3 to Device(), 7 to Device())
        var connected = true
        val speed =
            PeripheralContract<Int, Device>("test:speed", "test", "speed") { identity ->
                devices[identity.location]?.let { device ->
                    PeripheralEndpoint(device, device) {
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
        )
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
