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

package ru.lazyhat.ck.lang.runtime

import kotlinx.coroutines.runBlocking
import ru.lazyhat.ck.lang.frontend.FrontendSeverity
import ru.lazyhat.ck.lang.frontend.LanguageFrontend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanguageRuntimeTest {
    private val frontend = LanguageFrontend()

    @Test
    fun executesHostCallsThroughRuntimeBridge() {
        val artifact =
            frontend.compile(
                "runtime.ck",
                """
                import terminal;
                import system;
                import events;

                fun main() {
                    terminal.printLine("id=" + system.computerId());
                    val event: Event = events.pull("boot");
                    terminal.printLine(event.name);
                    sleep(1L);
                    yield();
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )

        val runtime = RecordingRuntime()
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("id=7", "boot"), runtime.lines)
        assertEquals(1, runtime.sleepCalls)
        assertEquals(1, runtime.yieldCalls)
        assertEquals(listOf<String?>("boot"), runtime.eventFilters)
    }

    @Test
    fun snapshotRoundTripRestoresExecutionState() {
        val artifact =
            frontend.compile(
                "snapshot.ck",
                """
                fun main() {
                    yield();
                }
                """.trimIndent(),
            )
        val module = requireNotNull(artifact.module)
        val vm = BytecodeVirtualMachine(module)

        val signal = vm.runUntilSignal()
        assertTrue(signal is VmSignal.Yield)

        val restored = BytecodeVirtualMachine(module, vm.snapshot())
        restored.resumeWith(VmValue.UnitValue)
        val endSignal = restored.runUntilSignal()
        assertTrue(endSignal is VmSignal.Halt)
    }
}

private class RecordingRuntime : ComputerRuntime {
    val lines = mutableListOf<String>()
    val eventFilters = mutableListOf<String?>()
    var sleepCalls = 0
    var yieldCalls = 0

    override val profile =
        ComputerProfile(
            id = "test",
            displayName = "Test Computer",
            cpuBudgetNanosPerSlice = 1_000_000,
            maxEventQueueSize = 16,
            terminalWidth = 16,
            terminalHeight = 8,
            colorTerminal = true,
            allowedCapabilities =
                setOf(
                    ComputerCapability.TERMINAL,
                    ComputerCapability.SYSTEM,
                    ComputerCapability.EVENTS,
                ),
        )

    override val system =
        object : ComputerSystemApi {
            override val computerId: Int = 7
            override val label: String? = "Test"
            override val currentTick: Long = 42L

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
                lines += text
            }

            override suspend fun printLine(text: String) {
                lines += text
            }

            override suspend fun clear() = Unit

            override suspend fun setCursor(
                x: Int,
                y: Int,
            ) = Unit
        }

    override val filesystem: ComputerFileSystemApi =
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

    override suspend fun pullEvent(filter: String?): VmEvent {
        eventFilters += filter
        return VmEvent("boot")
    }

    override suspend fun sleep(ticks: Long) {
        sleepCalls += ticks.toInt()
    }

    override suspend fun yield() {
        yieldCalls += 1
    }
}
