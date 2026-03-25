/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.compukterkraft.scripting.impl

import ru.lazyhat.compukterkraft.machine.ComputerCapability
import ru.lazyhat.compukterkraft.machine.ComputerFileSystemApi
import ru.lazyhat.compukterkraft.machine.ComputerPeripheralApi
import ru.lazyhat.compukterkraft.machine.ComputerProgram
import ru.lazyhat.compukterkraft.machine.ComputerProfile
import ru.lazyhat.compukterkraft.machine.ComputerRedstoneApi
import ru.lazyhat.compukterkraft.machine.ComputerRuntime
import ru.lazyhat.compukterkraft.machine.ComputerScriptBindings
import ru.lazyhat.compukterkraft.machine.ComputerSystemApi
import ru.lazyhat.compukterkraft.machine.ComputerTerminalApi
import ru.lazyhat.compukterkraft.machine.ComputerWorkspaceEntry
import ru.lazyhat.compukterkraft.machine.VmEvent
import ru.lazyhat.compukterkraft.scripting.api.ScriptDefinitionPresets
import ru.lazyhat.compukterkraft.scripting.api.ScriptingEnvironmentConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ComputerScriptBindingsTest {
    @Test
    fun executeProducesProgramThatUsesRuntimeBindings() {
        FakeRuntime.output.clear()
        val environment =
            ScriptingEnvironmentInitializerImpl().initialize(
                ScriptingEnvironmentConfig(
                    modId = "compukterkraft",
                    bundledScriptsRoot = "rom",
                    definitions = listOf(ScriptDefinitionPresets.computerKts("compukterkraft")),
                ),
            )

        val compilation =
            environment.compiler.compile(
                "test.cc.kts",
                """
                import terminal;
                import system;

                fun main() {
                    terminal.printLine("computer=" + system.computerId());
                }
                """.trimIndent(),
            )
        assertTrue(compilation.isSuccess, compilation.diagnostics.joinToString { it.message })

        val result = compilation.value!!.execute(ComputerScriptBindings.toProperties(FakeRuntime))
        assertTrue(result.isSuccess, result.exceptionMessage ?: result.diagnostics.joinToString { it.message })
        val program = assertIs<ComputerProgram>(result.value)
        runBlocking { program.run(FakeRuntime) }
        assertContains(FakeRuntime.output, "computer=42")
    }

    private object FakeRuntime : ComputerRuntime {
        val output = mutableListOf<String>()

        override val profile =
            ComputerProfile(
                id = "test",
                displayName = "Test",
                cpuBudgetNanosPerSlice = 1_000_000,
                maxEventQueueSize = 16,
                terminalWidth = 10,
                terminalHeight = 5,
                colorTerminal = true,
                allowedCapabilities = ComputerCapability.entries.toSet(),
            )

        override val system =
            object : ComputerSystemApi {
                override val computerId: Int = 42
                override val label: String? = "Test"
                override val currentTick: Long = 0

                override fun queueEvent(
                    name: String,
                    arguments: List<Any?>,
                ) = Unit

                override fun shutdown() = Unit

                override fun reboot() = Unit

                override fun log(message: String) = Unit
            }

        override val terminal =
            object : ComputerTerminalApi {
                override suspend fun write(text: String) {
                    output += text
                }

                override suspend fun printLine(text: String) {
                    output += text
                }

                override suspend fun clear() = Unit

                override suspend fun setCursor(
                    x: Int,
                    y: Int,
                ) = Unit
            }

        override val filesystem =
            object : ComputerFileSystemApi {
                override suspend fun exists(path: String): Boolean = false

                override suspend fun readText(path: String): String? = null

                override suspend fun writeText(
                    path: String,
                    text: String,
                ) = Unit

                override suspend fun list(path: String): List<ComputerWorkspaceEntry> = emptyList()
            }

        override val redstone: ComputerRedstoneApi = object : ComputerRedstoneApi {}
        override val peripherals: ComputerPeripheralApi = object : ComputerPeripheralApi {}

        override suspend fun pullEvent(filter: String?): VmEvent = VmEvent("noop")

        override suspend fun sleep(ticks: Long) = Unit

        override suspend fun yield() = Unit
    }
}
