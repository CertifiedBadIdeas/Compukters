/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package fixture

import ru.lazyhat.compukters.api.addon.AddonCallResult
import ru.lazyhat.compukters.api.addon.addonCompleted
import ru.lazyhat.compukters.api.addon.addonPending
import ru.lazyhat.compukters.api.commands.ProgramCommandProvider
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GeneratedCommandContractTest {
    @Test
    fun `opt in resolves the canonical operation without creating an asynchronous request`() {
        val values = ArrayList<Int>()
        val host =
            FixtureAddonContract.host(
                handler(setOf("awaitStressChange")) {
                    values += it
                    addonCompleted(Unit)
                },
            )
        val command = assertIs<ProgramCommandProvider>(host).commandBindings.single()
        val binding = FixtureAddonContract.guestApi(javaClass).bindings.single { it.callableName == "awaitStressChange" }
        assertEquals(binding.symbol, command.symbol)
        assertEquals(binding.operation, command.operation)
        assertEquals(HostResponse.UnitSuccess, command.execute(listOf(VmValue.I32(7))))
        assertEquals(listOf(7), values)
        assertTrue(host.poll(8).isEmpty())
    }

    @Test
    fun `waiting Unit handler is rejected without retaining or polling its continuation`() {
        var polled = false
        val host =
            FixtureAddonContract.host(
                handler(setOf("awaitStressChange")) {
                    addonPending {
                        polled = true
                        null
                    }
                },
            )
        val command = assertIs<ProgramCommandProvider>(host).commandBindings.single()
        val response = assertIs<HostResponse.Failure>(command.execute(listOf(VmValue.I32(7))))
        assertEquals(HostFailureKind.OTHER, response.kind)
        assertTrue(host.poll(8).isEmpty())
        assertTrue(!polled)
    }

    @Test
    fun `non Unit and unknown opt in names are rejected before use`() {
        for (name in listOf("speed", "unknown")) {
            assertFailsWith<IllegalArgumentException> { FixtureAddonContract.host(handler(setOf(name)) { addonCompleted(Unit) }) }
        }
        val host = FixtureAddonContract.host(handler(emptySet()) { addonCompleted(Unit) })
        assertTrue(assertIs<ProgramCommandProvider>(host).commandBindings.isEmpty())
    }

    private fun handler(
        names: Set<String>,
        command: (Int) -> AddonCallResult<Unit>,
    ): FixtureCapabilityHandler =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(FixtureCapabilityHandler::class.java)) { _, method, arguments ->
            when (method.name) {
                "getBatchCommands" -> names
                "awaitStressChange" -> command(arguments[0] as Int)
                else -> error("Unexpected handler method: ${method.name}")
            }
        } as FixtureCapabilityHandler
}
