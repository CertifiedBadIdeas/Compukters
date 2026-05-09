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

package ru.lazyhat.compukterkraft.core.device.vm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.lazyhat.compukterkraft.core.device.runtime.FirmwareProgramLoader
import ru.lazyhat.compukterkraft.core.device.runtime.LoadedFirmwareProgramSource
import ru.lazyhat.compukterkraft.core.device.runtime.RecordingRuntimeMetricsCollector
import ru.lazyhat.compukterkraft.core.device.runtime.test.runtimeProfile
import ru.lazyhat.compukterkraft.core.device.runtime.test.runtimeTestWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCapability
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCpuResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceMemoryResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProfile
import ru.lazyhat.compukterkraft.lang.runtime.DeviceQueueResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceStorageResources
import ru.lazyhat.compukterkraft.lang.runtime.VmState
import ru.lazyhat.compukterkraft.lang.runtime.VmStopReason
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BackgroundDeviceVmTest {
    private class StaticFirmwareLoader(
        private val source: String,
    ) : FirmwareProgramLoader {
        override fun load(path: String): LoadedFirmwareProgramSource = LoadedFirmwareProgramSource(path, source)
    }

    private fun runVmTicks(
        vm: BackgroundDeviceVm,
        ticks: Int = 8,
    ) = runBlocking {
        repeat(ticks) { tick ->
            vm.requestSlice(tick.toLong())
            kotlinx.coroutines.delay(10)
        }
    }

    private fun firmwareTestProfile(): DeviceProfile = runtimeProfile()

    @Test
    fun recordsRuntimeSchedulingMetrics() {
        runtimeTestWorkspace("vm-runtime-profiling") { workspace ->
            val metrics = RecordingRuntimeMetricsCollector()
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace.host,
                    firmwareLoader =
                        StaticFirmwareLoader(
                            """
                            pub fun main() {
                                var count: Int = 0;
                                while count < 3 {
                                    count = count + 1;
                                    sleep(1L);
                                }
                            }
                            """.trimIndent(),
                        ),
                    runtimeMetricsCollector = metrics,
                )

            assertTrue(vm.boot())
            runVmTicks(vm, ticks = 12)

            val snapshot = metrics.snapshot()
            assertTrue(snapshot.vm.sliceRequests > 0, snapshot.summary())
            assertTrue(snapshot.vm.slicePermitsSent > 0, snapshot.summary())
            assertTrue(snapshot.vm.slicePermitsReceived > 0, snapshot.summary())
            assertTrue(snapshot.vm.schedulingPoints > 0, snapshot.summary())
            assertTrue(snapshot.vm.executionWindows > 0, snapshot.summary())
            assertTrue(snapshot.vm.executionWindowNanos > 0, snapshot.summary())
        }
    }

    @Test
    fun ownsDisplayRegistryFrames() {
        runtimeTestWorkspace("vm-display-registry") { workspace ->
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace.host,
                    firmwareLoader =
                        StaticFirmwareLoader(
                            """
                            pub fun main() {
                                val displayId = display::primary();
                                display::fillRect(displayId, 1, 2, 3, 4, 63488);
                                display::present(displayId);
                            }
                            """.trimIndent(),
                        ),
                )

            val info = vm.attachDisplay(displayId = 9, width = 40, height = 20)

            assertEquals(9, info.displayId)
            assertEquals(40, info.width)
            assertEquals(20, info.height)
            val initialFrame = assertNotNull(vm.drainDisplayFrames().singleOrNull())
            assertEquals(9, initialFrame.displayId)
            assertTrue(vm.boot())
            runVmTicks(vm)
            val vmFrame = assertNotNull(vm.drainDisplayFrames().lastOrNull())
            assertEquals(9, vmFrame.displayId)
            assertEquals(40, vmFrame.width)
            assertEquals(20, vmFrame.height)
        }
    }

    @Test
    fun nativeDisplayPathDrainsAttachFullRefreshWhenEnabled() {
        System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        runtimeTestWorkspace("vm-native-display-attach") { workspace ->
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace.host,
                    firmwareLoader =
                        StaticFirmwareLoader(
                            """
                            pub fun main() {
                            }
                            """.trimIndent(),
                        ),
                    nativeDisplayEnabled = true,
                )

            val info = vm.attachDisplay(displayId = 4, width = 18, height = 18)
            val frames = vm.drainDisplayFrames()

            assertEquals(4, info.displayId)
            assertTrue(frames.any { it.displayId == 4 && it.fullRefresh })
        }
    }

    @Test
    fun nativeDisplayPathDrainsProgramFrameWhenEnabled() {
        System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        runtimeTestWorkspace("vm-native-display-program-frame") { workspace ->
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace.host,
                    firmwareLoader =
                        StaticFirmwareLoader(
                            """
                            pub fun main() {
                                val displayId = display::primary();
                                display::fillRect(displayId, 0, 0, 2, 2, 2016);
                                display::present(displayId);
                            }
                            """.trimIndent(),
                        ),
                    nativeDisplayEnabled = true,
                )

            vm.attachDisplay(displayId = 4, width = 18, height = 18)
            assertEquals(1, vm.drainDisplayFrames().size)
            assertTrue(vm.boot())
            runVmTicks(vm)
            val frames = vm.drainDisplayFrames()

            assertTrue(frames.any { it.displayId == 4 && it.sequence >= 2L && !it.fullRefresh })
        }
    }

    @Test
    fun displayAttachQueuesVmEvent() {
        runtimeTestWorkspace("vm-display-attach-event") { workspace ->
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace.host,
                    firmwareLoader =
                        StaticFirmwareLoader(
                            """
                            pub fun main() {
                                events::pull("display_attach");
                                val displayId = display::primary();
                                display::fillRect(displayId, 0, 0, 1, 1, 63488);
                                display::present(displayId);
                            }
                            """.trimIndent(),
                        ),
                )

            assertTrue(vm.boot())
            runVmTicks(vm, ticks = 2)
            vm.attachDisplay(displayId = 9, width = 8, height = 8)
            runVmTicks(vm, ticks = 4)

            val frames = vm.drainDisplayFrames()
            assertEquals(listOf(1L, 2L), frames.map { it.sequence })
        }
    }

    @Test
    fun parentCanSpawnChildAndExchangeIpcText() {
        runtimeTestWorkspace("firmware-spawn-ipc-child") { workspace ->
            val logs = mutableListOf<String>()
            workspace.writeProgram(
                1,
                "boot.ck",
                """
                pub fun main() {
                    val inputText: String = strings::beforeSpace(process::argument())
                    val rest1: String = strings::afterSpace(process::argument())
                    val outputText: String = strings::beforeSpace(rest1)
                    val input: Int = strings::toInt(inputText)
                    val output: Int = strings::toInt(outputText)
                    ipc::write(output, ipc::read(input) + "child-")
                }
                """.trimIndent(),
            )
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger(logs::add),
                    workspace = workspace.host,
                    firmwareLoader =
                        StaticFirmwareLoader(
                            """
                            pub fun main() {
                                val childInput: Int = ipc::open()
                                val childOutput: Int = ipc::open()
                                val pid: Int = process::spawn("boot.ck", childInput + " " + childOutput + " 0")
                                ipc::write(childInput, "parent-")
                                val text: String = ipc::read(childOutput)
                                val code: Int = process::wait(pid)
                                system::log(text + "code=" + code)
                                while true { sleep(20L) }
                            }
                            """.trimIndent(),
                        ),
                )

            vm.boot()
            runVmTicks(vm, ticks = 40)

            assertTrue(logs.any { it.contains("parent-child-code=0") }, "state=${vm.snapshot().state} logs=$logs")
            assertTrue(vm.snapshot().state.isActive, vm.snapshot().state.toString())
        }
    }

    @Test
    fun processRunWritesLaunchErrorsToTaggedStderr() {
        runtimeTestWorkspace("process-stderr-launch") { workspace ->
            val logs = mutableListOf<String>()
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger(logs::add),
                    workspace = workspace.host,
                    firmwareLoader =
                        StaticFirmwareLoader(
                            """
                            pub fun main() {
                                val input: Int = ipc::open()
                                val output: Int = ipc::open()
                                val error: Int = ipc::open()
                                val code: Int = process::run("missing.ck", "stdio-v1 " + input + " " + output + " " + error + " ")
                                system::log("code=" + code)
                                system::log(ipc::read(error))
                            }
                            """.trimIndent(),
                        ),
                )

            assertTrue(vm.boot())
            runVmTicks(vm, ticks = 24)

            assertTrue(logs.any { it.contains("code=1") }, logs.toString())
            assertTrue(logs.any { it.contains("Program not found: missing.ck") }, logs.toString())
        }
    }

    @Test
    fun processRunWritesCompilationErrorsToTaggedStderr() {
        runtimeTestWorkspace("process-stderr-compile") { workspace ->
            workspace.writeProgram(1, "bad.ck", "pub fun main() { val x: Int = \"bad\"; }")
            val logs = mutableListOf<String>()
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger(logs::add),
                    workspace = workspace.host,
                    firmwareLoader =
                        StaticFirmwareLoader(
                            """
                            pub fun main() {
                                val input: Int = ipc::open()
                                val output: Int = ipc::open()
                                val error: Int = ipc::open()
                                val code: Int = process::run("bad.ck", "stdio-v1 " + input + " " + output + " " + error + " ")
                                system::log("code=" + code)
                                system::log(ipc::read(error))
                            }
                            """.trimIndent(),
                        ),
                )

            assertTrue(vm.boot())
            runVmTicks(vm, ticks = 24)

            assertTrue(logs.any { it.contains("code=1") }, logs.toString())
            assertTrue(logs.any { it.contains("Compilation Error in bad.ck") }, logs.toString())
        }
    }

    @Test
    fun firmwareReportsMissingBootFileAndStaysActive() {
        runtimeTestWorkspace("firmware-missing-boot") { workspace ->
            val logs = mutableListOf<String>()
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger(logs::add),
                    workspace = workspace.host,
                    firmwareLoader =
                        StaticFirmwareLoader(
                            """
                            pub fun main() {
                                val input: Int = ipc::open()
                                val output: Int = ipc::open()
                                val error: Int = ipc::open()
                                val code: Int = process::run("boot.ck", "stdio-v1 " + input + " " + output + " " + error + " ")
                                system::log("code=" + code)
                                system::log(ipc::read(error))
                                while true { sleep(20L) }
                            }
                            """.trimIndent(),
                        ),
                )

            vm.boot()
            runVmTicks(vm)

            assertTrue(logs.any { it.contains("Program not found: boot.ck") }, logs.toString())
            assertTrue(logs.any { it.contains("code=1") }, logs.toString())
            assertTrue(vm.snapshot().state.isActive, vm.snapshot().state.toString())
        }
    }

    @Test
    fun firmwareReportsBootCompileErrorAndStaysActive() {
        runtimeTestWorkspace("firmware-invalid-boot") { workspace ->
            workspace.writeProgram(1, "boot.ck", "fun main() {}")
            val logs = mutableListOf<String>()
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger(logs::add),
                    workspace = workspace.host,
                    firmwareLoader =
                        StaticFirmwareLoader(
                            """
                            pub fun main() {
                                val input: Int = ipc::open()
                                val output: Int = ipc::open()
                                val error: Int = ipc::open()
                                val code: Int = process::run("boot.ck", "stdio-v1 " + input + " " + output + " " + error + " ")
                                system::log("code=" + code)
                                system::log(ipc::read(error))
                                while true { sleep(20L) }
                            }
                            """.trimIndent(),
                        ),
                )

            vm.boot()
            runVmTicks(vm)

            assertTrue(logs.any { it.contains("Compilation Error in boot.ck") }, logs.toString())
            assertTrue(logs.any { it.contains("pub fun main") }, logs.toString())
            assertTrue(logs.any { it.contains("code=1") }, logs.toString())
            assertTrue(vm.snapshot().state.isActive, vm.snapshot().state.toString())
        }
    }

    @Test
    fun firmwareCanUseAmbientFilesystemModuleAndStayAlive() {
        runtimeTestWorkspace("compukterkraft-background-vm-success") { workspace ->
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = runtimeProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace.host,
                    firmwareLoader =
                        StaticFirmwareLoader(
                            """
                            pub fun main() {
                                if (false) { filesystem::list() }
                                while true { sleep(20L) }
                            }
                            """.trimIndent(),
                        ),
                )

            vm.boot()
            runVmTicks(vm)

            assertTrue(vm.snapshot().state.isActive, vm.snapshot().state.toString())
        }
    }

    @Test
    fun surfacesRomLimitFailureAsCrashedState() {
        val root = createTempDirectory("compukterkraft-background-vm")

        try {
            val workspace = DeviceWorkspaceHost(root)

            val profile =
                DeviceProfile(
                    id = "tiny-rom",
                    displayName = "Tiny ROM",
                    cpuBudgetNanosPerSlice = 1_000_000,
                    maxEventQueueSize = 16,
                    allowedCapabilities = setOf(DeviceCapability.SYSTEM),
                    resources =
                        DeviceResources(
                            cpu = DeviceCpuResources(wallTimeGuardNanosPerSlice = 1_000_000),
                            memory = DeviceMemoryResources(),
                            storage = DeviceStorageResources(programRomBytes = 1, diskBytes = 1024),
                            queues = DeviceQueueResources(eventQueueSlots = 16, hostCallQueueSlots = 16),
                        ),
                )

            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = profile,
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace,
                    firmwareLoader = StaticFirmwareLoader("pub fun main() { }"),
                )

            val terminalState =
                runBlocking {
                    val terminalState =
                        async {
                            withTimeout(5_000) {
                                vm.terminalStates.first()
                            }
                        }

                    kotlinx.coroutines.yield()
                    vm.boot()
                    vm.requestSlice(0)
                    terminalState.await()
                }

            assertTrue(terminalState is VmState.Crashed)
            assertTrue(terminalState.errorMessage?.contains("ROM limit") == true)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun bootRejectsAmbientModuleWhenVmRegistryDoesNotExposeIt() {
        val root = createTempDirectory("compukterkraft-background-vm")

        try {
            val workspace = DeviceWorkspaceHost(root)

            val profile =
                DeviceProfile(
                    id = "terminal-only",
                    displayName = "Terminal Only",
                    cpuBudgetNanosPerSlice = 1_000_000,
                    maxEventQueueSize = 16,
                    allowedCapabilities = setOf(DeviceCapability.SYSTEM),
                    resources =
                        DeviceResources(
                            cpu = DeviceCpuResources(wallTimeGuardNanosPerSlice = 1_000_000),
                            memory = DeviceMemoryResources(),
                            storage = DeviceStorageResources(programRomBytes = 4096, diskBytes = 1024),
                            queues = DeviceQueueResources(eventQueueSlots = 16, hostCallQueueSlots = 16),
                        ),
                )

            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = profile,
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace,
                    firmwareLoader = StaticFirmwareLoader("pub fun main() { if (false) { filesystem::list() } }"),
                )

            val terminalState =
                runBlocking {
                    val terminalState =
                        async {
                            withTimeout(5_000) {
                                vm.terminalStates.first()
                            }
                        }

                    kotlinx.coroutines.yield()
                    vm.boot()
                    vm.requestSlice(0)
                    terminalState.await()
                }

            assertTrue(terminalState is VmState.Crashed)
            assertTrue(terminalState.errorMessage?.contains("Unknown namespace `filesystem`") == true)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
