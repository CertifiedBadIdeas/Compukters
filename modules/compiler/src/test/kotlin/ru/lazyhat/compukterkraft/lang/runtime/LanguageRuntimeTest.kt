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

package ru.lazyhat.compukterkraft.lang.runtime

import kotlinx.coroutines.runBlocking
import ru.lazyhat.compukterkraft.lang.api.BuiltinFunction
import ru.lazyhat.compukterkraft.lang.api.BuiltinModule
import ru.lazyhat.compukterkraft.lang.api.BuiltinRegistry
import ru.lazyhat.compukterkraft.lang.api.ModuleOrigin
import ru.lazyhat.compukterkraft.lang.frontend.FrontendSeverity
import ru.lazyhat.compukterkraft.lang.frontend.LanguageBuiltins
import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun usesInstructionBudgetFromProfileResources() {
        val artifact =
            frontend.compile(
                "budget.ck",
                """
                fun main() {
                    val a: Int = 1;
                    val b: Int = 2;
                    val c: Int = a + b;
                    val d: Int = c + 1;
                }
                """.trimIndent(),
            )

        val lowBudgetRuntime = RecordingRuntime(instructionsPerSlice = 2)
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(lowBudgetRuntime)
        }

        val highBudgetRuntime = RecordingRuntime(instructionsPerSlice = 128)
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(highBudgetRuntime)
        }

        assertTrue(lowBudgetRuntime.yieldCalls > 0)
        assertEquals(0, highBudgetRuntime.yieldCalls)
    }

    @Test
    fun failsWhenVmMemoryExceedsProfileLimit() {
        val artifact =
            frontend.compile(
                "memory.ck",
                """
                fun main() {
                    val text: String = "123456789";
                }
                """.trimIndent(),
            )

        val runtime = RecordingRuntime(vmRamBytes = 4)

        assertFailsWith<IllegalStateException> {
            runBlocking {
                BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
            }
        }
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

    @Test
    fun stdoutWriteReachesRuntimeThroughHostBridge() {
        val artifact =
            frontend.compile(
                "stdout.ck",
                """
                import stdout;

                fun main() {
                    stdout.write("Hi");
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

        assertEquals(listOf("Hi"), runtime.stdioWrites)
    }

    @Test
    fun exposesShellBuiltinsThroughRuntimeBridge() {
        val artifact =
            frontend.compile(
                "shell.ck",
                """
                import terminal;
                import filesystem;
                import process;
                import strings;

                fun main() {
                    terminal.printLine(terminal.readLine("> "));
                    terminal.printLine(filesystem.list());
                    terminal.printLine(process.currentDirectory());
                    terminal.printLine(process.argument());
                    terminal.printLine(strings.beforeSpace("mkdir test"));
                    terminal.printLine(strings.afterSpace("mkdir test"));
                    if (filesystem.makeDir("tmp")) {
                        terminal.printLine("mk");
                    } else {
                        terminal.printLine("no");
                    }
                    if (process.changeDirectory("tmp")) {
                        terminal.printLine("cd");
                    } else {
                        terminal.printLine("stay");
                    }
                    terminal.printLine(process.currentDirectory());
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )

        val runtime = RecordingRuntime(argument = "boot")
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(
            listOf("typed", "docs/ readme.txt", "", "boot", "mkdir", "test", "mk", "cd", "tmp"),
            runtime.lines,
        )
        assertEquals(listOf("tmp"), runtime.createdDirectories)
    }

    @Test
    fun executesElseIfChains() {
        val artifact =
            frontend.compile(
                "elseif.ck",
                """
                import terminal;

                fun main() {
                    val x: Int = 2;
                    if (x == 1) {
                        terminal.printLine("one");
                    } else if (x == 2) {
                        terminal.printLine("two");
                    } else if (x == 3) {
                        terminal.printLine("three");
                    } else {
                        terminal.printLine("other");
                    }
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

        assertEquals(listOf("two"), runtime.lines)
    }

    @Test
    fun executesElseIfFallsToElse() {
        val artifact =
            frontend.compile(
                "elseif_else.ck",
                """
                import terminal;

                fun main() {
                    val x: Int = 99;
                    if (x == 1) {
                        terminal.printLine("one");
                    } else if (x == 2) {
                        terminal.printLine("two");
                    } else {
                        terminal.printLine("other");
                    }
                }
                """.trimIndent(),
            )

        val runtime = RecordingRuntime()
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("other"), runtime.lines)
    }

    @Test
    fun executesWhenWithSubject() {
        val artifact =
            frontend.compile(
                "when_subject.ck",
                """
                import terminal;

                fun main() {
                    val x: Int = 2;
                    when(x) {
                        1 -> {
                            terminal.printLine("one");
                        }
                        2 -> {
                            terminal.printLine("two");
                        }
                        3 -> {
                            terminal.printLine("three");
                        }
                        else -> {
                            terminal.printLine("other");
                        }
                    }
                }
                """.trimIndent(),
            )

        val runtime = RecordingRuntime()
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("two"), runtime.lines)
    }

    @Test
    fun executesWhenWithSubjectMultipleValues() {
        val artifact =
            frontend.compile(
                "when_multi.ck",
                """
                import terminal;

                fun main() {
                    val x: Int = 3;
                    when(x) {
                        1 -> {
                            terminal.printLine("one");
                        }
                        2, 3 -> {
                            terminal.printLine("two or three");
                        }
                        else -> {
                            terminal.printLine("other");
                        }
                    }
                }
                """.trimIndent(),
            )

        val runtime = RecordingRuntime()
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("two or three"), runtime.lines)
    }

    @Test
    fun executesWhenWithSubjectElseBranch() {
        val artifact =
            frontend.compile(
                "when_else.ck",
                """
                import terminal;

                fun main() {
                    val x: Int = 99;
                    when(x) {
                        1 -> {
                            terminal.printLine("one");
                        }
                        2 -> {
                            terminal.printLine("two");
                        }
                        else -> {
                            terminal.printLine("other");
                        }
                    }
                }
                """.trimIndent(),
            )

        val runtime = RecordingRuntime()
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("other"), runtime.lines)
    }

    @Test
    fun executesWhenWithoutSubject() {
        val artifact =
            frontend.compile(
                "when_no_subject.ck",
                """
                import terminal;

                fun main() {
                    val x: Int = 5;
                    when {
                        x > 10 -> {
                            terminal.printLine("big");
                        }
                        x > 0 -> {
                            terminal.printLine("positive");
                        }
                        else -> {
                            terminal.printLine("non-positive");
                        }
                    }
                }
                """.trimIndent(),
            )

        val runtime = RecordingRuntime()
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("positive"), runtime.lines)
    }

    @Test
    fun executesWhenWithoutSubjectElse() {
        val artifact =
            frontend.compile(
                "when_no_subject_else.ck",
                """
                import terminal;

                fun main() {
                    val x: Int = 0;
                    when {
                        x > 10 -> {
                            terminal.printLine("big");
                        }
                        x > 0 -> {
                            terminal.printLine("positive");
                        }
                        else -> {
                            terminal.printLine("zero or negative");
                        }
                    }
                }
                """.trimIndent(),
            )

        val runtime = RecordingRuntime()
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("zero or negative"), runtime.lines)
    }

    @Test
    fun routesMonitorExistsThroughRuntimeBridgeWhenDeviceIsMissing() {
        val frontend =
            LanguageFrontend(
                BuiltinRegistry(
                    modules =
                        LanguageBuiltins.defaultRuntimeRegistry.modules +
                            BuiltinModule(
                                name = "monitor",
                                documentation = "Connected monitor registry.",
                                functions =
                                    listOf(
                                        BuiltinFunction(
                                            "exists",
                                            emptyList(),
                                            "Bool",
                                            "Returns true when any monitor is connected.",
                                        ),
                                    ),
                                origin = ModuleOrigin.OPTIONAL_VM,
                            ),
                    globals = LanguageBuiltins.defaultRuntimeRegistry.globals,
                    builtinTypes = LanguageBuiltins.defaultRuntimeRegistry.builtinTypes,
                ),
            )

        val artifact =
            frontend.compile(
                "monitor.ck",
                """
                import terminal;
                import monitor;

                fun main() {
                    if (monitor.exists()) {
                        terminal.printLine("connected");
                    } else {
                        terminal.printLine("missing");
                    }
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

        assertEquals(listOf("missing"), runtime.lines)
    }

    @Test
    fun routesMonitorExistsThroughRuntimeBridgeWhenDeviceIsConnected() {
        val frontend =
            LanguageFrontend(
                BuiltinRegistry(
                    modules =
                        LanguageBuiltins.defaultRuntimeRegistry.modules +
                            BuiltinModule(
                                name = "monitor",
                                documentation = "Connected monitor registry.",
                                functions =
                                    listOf(
                                        BuiltinFunction(
                                            "exists",
                                            emptyList(),
                                            "Bool",
                                            "Returns true when any monitor is connected.",
                                        ),
                                    ),
                                origin = ModuleOrigin.OPTIONAL_VM,
                            ),
                    globals = LanguageBuiltins.defaultRuntimeRegistry.globals,
                    builtinTypes = LanguageBuiltins.defaultRuntimeRegistry.builtinTypes,
                ),
            )

        val artifact =
            frontend.compile(
                "monitor_connected.ck",
                """
                import terminal;
                import monitor;

                fun main() {
                    if (monitor.exists()) {
                        terminal.printLine("connected");
                    } else {
                        terminal.printLine("missing");
                    }
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )

        val runtime = RecordingRuntime(monitorConnected = true)
        runBlocking {
            BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
        }

        assertEquals(listOf("connected"), runtime.lines)
    }
}

private class RecordingRuntime(
    private val argument: String = "",
    private val instructionsPerSlice: Int = 64,
    private val vmRamBytes: Long = 64 * 1024,
    private val monitorConnected: Boolean = false,
) : DeviceRuntime {
    val lines = mutableListOf<String>()
    val eventFilters = mutableListOf<String?>()
    val createdDirectories = mutableListOf<String>()
    var sleepCalls = 0
    var yieldCalls = 0

    override val profile =
        DeviceProfile(
            id = "test",
            displayName = "Test Computer",
            cpuBudgetNanosPerSlice = 1_000_000,
            maxEventQueueSize = 16,
            terminalWidth = 16,
            terminalHeight = 8,
            colorTerminal = true,
            allowedCapabilities =
                setOf(
                    DeviceCapability.TERMINAL,
                    DeviceCapability.FILESYSTEM,
                    DeviceCapability.SYSTEM,
                    DeviceCapability.EVENTS,
                    DeviceCapability.PERIPHERALS,
                ),
            resources =
                DeviceResources(
                    cpu =
                        DeviceCpuResources(
                            instructionsPerSlice = instructionsPerSlice,
                            wallTimeGuardNanosPerSlice = 1_000_000,
                        ),
                    memory = DeviceMemoryResources(vmRamBytes = vmRamBytes),
                    storage =
                        DeviceStorageResources(
                            programRomBytes = 64 * 1024,
                            diskBytes = 256 * 1024,
                        ),
                    queues =
                        DeviceQueueResources(
                            eventQueueSlots = 16,
                            hostCallQueueSlots = 16,
                        ),
                ),
        )

    override val system =
        object : DeviceSystemApi {
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
        object : DeviceTerminalApi {
            override fun write(text: String) {
                lines += text
            }

            override fun printLine(text: String) {
                lines += text
            }

            override suspend fun readLine(prompt: String): String = "typed"

            override fun clear() = Unit

            override fun setCursor(
                x: Int,
                y: Int,
            ) = Unit
        }

    val stdioWrites: MutableList<String> = mutableListOf()

    override val stdio: ComputerStdioApi =
        object : ComputerStdioApi {
            override fun writeString(text: String) {
                stdioWrites += text
            }
        }

    override val filesystem: DeviceFileSystemApi =
        object : DeviceFileSystemApi {
            override suspend fun exists(path: String): Boolean = path == "readme.txt" || path == "docs" || path == "tmp"

            override suspend fun isDirectory(path: String): Boolean = path == "docs" || path == "tmp"

            override suspend fun readText(path: String): String? = null

            override suspend fun writeText(
                path: String,
                text: String,
            ) = Unit

            override suspend fun makeDirectory(path: String): Boolean {
                createdDirectories += path
                return true
            }

            override suspend fun remove(path: String): Boolean = true

            override suspend fun list(path: String): List<ComputerWorkspaceEntry> =
                listOf(
                    ComputerWorkspaceEntry("docs", directory = true),
                    ComputerWorkspaceEntry("readme.txt", directory = false),
                )
        }

    override val process =
        object : DeviceProcessApi {
            private var currentDirectory = ""

            override val workingDirectory: String
                get() = currentDirectory

            override val argument: String
                get() = this@RecordingRuntime.argument

            override suspend fun changeDirectory(path: String): Boolean {
                currentDirectory = path
                return true
            }

            override suspend fun run(
                path: String,
                argument: String,
            ): Int = 0
        }

    override val redstone: DeviceRedstoneApi = object : DeviceRedstoneApi {}
    override val peripherals: DevicePeripheralApi =
        object : DevicePeripheralApi {
            override fun monitorExists(): Boolean = monitorConnected
        }

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
