/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.device.runtime.peripheral

import ru.lazyhat.compukters.api.addon.ProgramAddonDispatch
import ru.lazyhat.compukters.api.addon.ProgramAddonRequest
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PeripheralProgramHostTest {
    @Test
    fun `discovery requests share typed handles and reset closes snapshots`() {
        val endpoint = Any()
        val identity = PeripheralIdentity("test", 3, "meter")
        val contract = PeripheralContract<Int, Any>("test:meter", "test", "meter") { PeripheralEndpoint(endpoint, endpoint) { true } }
        val session = PeripheralSession(listOf(contract), { listOf(identity) }, { emptyList() }, { _, _ -> null })
        val host = PeripheralProgramHost(session)
        val snapshot = assertIs<HostResponse.IntSuccess>(call(host, 0, VmValue.StringValue(contract.id))).value
        assertEquals(HostResponse.IntSuccess(1), call(host, 1, VmValue.I32(snapshot)))
        val handle = assertIs<HostResponse.IntSuccess>(call(host, 2, VmValue.I32(snapshot), VmValue.I32(0))).value
        assertEquals(endpoint, session.endpoint(contract, handle))
        assertEquals(HostResponse.IntSuccess(0), call(host, 4, VmValue.StringValue(contract.id), VmValue.I32(0)))
        assertEquals(HostResponse.IntSuccess(0), call(host, 5, VmValue.StringValue(contract.id), VmValue.StringValue("missing")))
        assertEquals(HostResponse.UnitSuccess, call(host, 3, VmValue.I32(snapshot)))
        val next = assertIs<HostResponse.IntSuccess>(call(host, 0, VmValue.StringValue(contract.id))).value
        host.reset()
        assertIs<HostResponse.Failure>(call(host, 1, VmValue.I32(next)))
    }

    @Test
    fun `malformed requests and unavailable contracts are failures rather than optional absence`() {
        val session = PeripheralSession<Int>(emptyList(), { emptyList() }, { emptyList() }, { _, _ -> null })
        val host = PeripheralProgramHost(session)
        for ((operation, arguments) in listOf(
            0 to emptyList(),
            0 to listOf(VmValue.I32(1)),
            2 to listOf(VmValue.I32(1)),
            3 to listOf(VmValue.I32(1), VmValue.I32(2)),
            99 to emptyList(),
        )) {
            assertEquals(HostFailureKind.OTHER, assertIs<HostResponse.Failure>(call(host, operation, *arguments.toTypedArray())).kind)
        }
        assertEquals(HostFailureKind.UNAVAILABLE, assertIs<HostResponse.Failure>(call(host, 0, VmValue.StringValue("missing:meter"))).kind)
    }

    private fun call(
        host: PeripheralProgramHost,
        operation: Int,
        vararg arguments: VmValue,
    ): HostResponse =
        assertIs<ProgramAddonDispatch.Completed>(
            host.dispatch(
                ProgramAddonRequest(
                    VmHostRequestIdentity(1, 1),
                    PeripheralProgramHost.SCHEMA.identity,
                    operation,
                    arguments.toList(),
                ),
            ),
        ).response
}
