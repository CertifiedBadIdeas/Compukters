/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ru.lazyhat.compukters.lang.runtime.integration

import kotlinx.coroutines.runBlocking
import ru.lazyhat.compukters.lang.runtime.capability.CapabilityRegistry
import ru.lazyhat.compukters.lang.runtime.capability.TerminalCapability
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime
import ru.lazyhat.compukters.lang.runtime.vm.VmSession
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NativeBridgeIntegrationTest {
    @Test
    fun `terminal artifact runs through Kotlin JNI and Rust VM`() =
        runBlocking {
            VmRuntime.loadNativeLibrary(Path.of(requiredProperty("compukter.jni.library")))
            val artifact = decodeHex(Path.of(requiredProperty("compukter.vm.terminalFixture")).readText())
            val output = ByteArrayOutputStream()
            val terminal = TerminalCapability(ByteArrayInputStream("answer\r\n".encodeToByteArray()), output)
            val capabilities = CapabilityRegistry(listOf(terminal))

            VmSession.open(artifact).use { session ->
                repeat(MAXIMUM_ADVANCES) {
                    when (val outcome = session.advance(GUEST_BUDGET, MAINTENANCE_BUDGET)) {
                        is VmOutcome.HostRequest -> {
                            session.resume(outcome.request.id, capabilities.dispatch(outcome.request))
                        }

                        is VmOutcome.Halted -> {
                            assertIs<VmValue.I32>(outcome.value)
                            assertEquals("> \ud83d\ude00> \ud83d\ude00\n", output.toString(Charsets.UTF_8))
                            return@runBlocking
                        }

                        VmOutcome.SliceExhausted -> {
                            return@repeat
                        }

                        else -> {
                            error("unexpected VM outcome: $outcome")
                        }
                    }
                }
            }
            error("terminal artifact did not halt within $MAXIMUM_ADVANCES advances")
        }

    private fun requiredProperty(name: String): String = requireNotNull(System.getProperty(name)) { "missing test system property $name" }

    private fun decodeHex(encoded: String): ByteArray {
        val value = encoded.trim()
        require(value.length % 2 == 0) { "fixture contains incomplete hexadecimal byte" }
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private companion object {
        const val GUEST_BUDGET = 64
        const val MAINTENANCE_BUDGET = 64
        const val MAXIMUM_ADVANCES = 10_000
    }
}
