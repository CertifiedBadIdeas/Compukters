/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.device.runtime.peripheral

import ru.lazyhat.compukters.api.addon.ProgramAddonDispatch
import ru.lazyhat.compukters.api.addon.ProgramAddonRequest
import ru.lazyhat.compukters.core.device.runtime.program.programScopedAddonHostOf
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PeripheralOwnershipTest {
    private class World {
        val ownership = PeripheralOwnership()
        var device = Any()
        var connected = true
        val primary =
            PeripheralContract<Int, Any>("test:primary", "test", "device") {
                val instance = device
                PeripheralEndpoint(instance, instance) { connected && device === instance }
            }
        val alias = PeripheralContract("test:alias", "test", "device", primary.resolve)

        fun process() =
            PeripheralSession(
                listOf(primary, alias),
                discover = { listOf(PeripheralIdentity("test", 1, "device")) },
                side = { listOf(PeripheralIdentity("test", 1, "device")) },
                lookupName = { _, _ -> PeripheralIdentity("test", 1, "device") },
                ownership = ownership,
            )
    }

    @Test
    fun `process stop releases ownership while suspended parent retains its device`() {
        val world = World()
        val computer = programScopedAddonHostOf { PeripheralProgramHost(world.process()) }
        val competitor = programScopedAddonHostOf { PeripheralProgramHost(world.process()) }

        fun acquire(
            host: ru.lazyhat.compukters.api.addon.ProgramAddonHost,
            process: Long,
        ): HostResponse =
            (
                host.dispatch(
                    ProgramAddonRequest(
                        VmHostRequestIdentity(1, process + 1),
                        PeripheralProgramHost.SCHEMA.identity,
                        4,
                        listOf(VmValue.StringValue(world.primary.id), VmValue.I32(0)),
                        process,
                    ),
                ) as ProgramAddonDispatch.Completed
            ).response
        computer.programStarted(1)
        assertEquals(HostResponse.IntSuccess(1), acquire(computer, 1))
        computer.programStarted(2)
        assertEquals(HostFailureKind.UNAVAILABLE, (acquire(computer, 2) as HostResponse.Failure).kind)
        computer.programStopped(2)
        assertEquals(HostFailureKind.UNAVAILABLE, (acquire(competitor, 0) as HostResponse.Failure).kind)
        computer.programStopped(1)
        assertEquals(HostResponse.IntSuccess(1), acquire(competitor, 0))
        computer.close()
        competitor.close()
    }

    @Test
    fun `acquisition is exclusive between process sessions even before device operations`() {
        val world = World()
        val first = world.process()
        val second = world.process()
        first.named(world.primary.id, "device")
        val failure = assertFailsWith<PeripheralFailure> { second.at(world.primary.id, 0) }
        assertEquals(HostFailureKind.UNAVAILABLE, failure.kind)
        first.reset()
        second.at(world.primary.id, 0)
    }

    @Test
    fun `another contract cannot bypass ownership and same process aliases retain it until last close`() {
        val world = World()
        val first = world.process()
        val second = world.process()
        val primary = first.at(world.primary.id, 0)
        val alias = first.at(world.alias.id, 0)
        first.close(world.primary, primary)
        assertFailsWith<PeripheralFailure> { second.at(world.primary.id, 0) }
        assertFailsWith<PeripheralFailure> { second.at(world.alias.id, 0) }
        first.close(world.alias, alias)
        second.at(world.primary.id, 0)
    }

    @Test
    fun `discovery snapshots never reserve all devices`() {
        val world = World()
        val first = world.process()
        val second = world.process()
        val snapshot = first.openSnapshot(world.primary.id)
        assertEquals(1, first.snapshotSize(snapshot))
        second.at(world.primary.id, 0)
        assertFailsWith<PeripheralFailure> { first.snapshotGet(snapshot, 0) }
        first.closeSnapshot(snapshot)
        second.reset()
        first.at(world.primary.id, 0)
    }

    @Test
    fun `disconnect expires ownership without guest calls and reconnect never revives old handle`() {
        val world = World()
        val first = world.process()
        val handle = first.at(world.primary.id, 0)
        world.connected = false
        world.ownership.expire()
        world.connected = true
        world.process().at(world.primary.id, 0)
        assertFailsWith<PeripheralFailure> { first.endpoint(world.primary, handle) }
    }

    @Test
    fun `replacement is a new ownership identity and stale owner cannot block it`() {
        val world = World()
        val first = world.process()
        val old = first.at(world.primary.id, 0)
        world.device = Any()
        val second = world.process()
        val replacement = second.at(world.primary.id, 0)
        assertFailsWith<PeripheralFailure> { first.endpoint(world.primary, old) }
        assertEquals(world.device, second.endpoint(world.primary, replacement))
    }
}
