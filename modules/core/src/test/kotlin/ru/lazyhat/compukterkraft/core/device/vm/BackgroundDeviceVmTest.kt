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
import ru.lazyhat.compukterkraft.core.device.runtime.test.runtimeProfile
import ru.lazyhat.compukterkraft.core.device.runtime.test.runtimeTestWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCapability
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCpuResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceMemoryResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProfile
import ru.lazyhat.compukterkraft.lang.runtime.DeviceQueueResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceResources
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot
import ru.lazyhat.compukterkraft.lang.runtime.DeviceStorageResources
import ru.lazyhat.compukterkraft.lang.runtime.VmState
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

    private fun ScreenBufferSnapshot.visibleText(): String =
        (0 until height)
            .joinToString("\n") { y ->
                (0 until width).joinToString("") { x -> charAt(x, y).toString() }.trimEnd()
            }.trim()

    private fun runVmTicks(
        vm: BackgroundDeviceVm,
        ticks: Int = 8,
    ) = runBlocking {
        repeat(ticks) { tick ->
            vm.requestSlice(tick.toLong())
            kotlinx.coroutines.delay(10)
        }
    }

    private fun firmwareTestProfile(): DeviceProfile = runtimeProfile().copy(terminalWidth = 120, terminalHeight = 16)

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
    fun bootsFirmwareAndRunsUserBootFileFromWorkspace() {
        runtimeTestWorkspace("firmware-runs-user-boot") { workspace ->
            workspace.writeProgram(1, "boot.ck", "pub fun main() { terminal::println(\"from boot\"); }")
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
                                terminal::println("from bios")
                                val code: Int = process::run("boot.ck")
                                terminal::println("code=" + code)
                                while true { sleep(20L) }
                            }
                            """.trimIndent(),
                        ),
                )

            vm.boot()
            runVmTicks(vm)

            val text = vm.forceScreenSnapshot().visibleText()
            assertTrue(text.contains("from bios"), text)
            assertTrue(text.contains("from boot"), text)
            assertTrue(text.contains("code=0"), text)
            assertTrue(vm.snapshot().state.isActive, vm.snapshot().state.toString())
        }
    }

    @Test
    fun firmwareReportsMissingBootFileAndStaysActive() {
        runtimeTestWorkspace("firmware-missing-boot") { workspace ->
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
                                val code: Int = process::run("boot.ck")
                                terminal::println("code=" + code)
                                while true { sleep(20L) }
                            }
                            """.trimIndent(),
                        ),
                )

            vm.boot()
            runVmTicks(vm)

            val text = vm.forceScreenSnapshot().visibleText()
            assertTrue(text.contains("Program not found: boot.ck"), text)
            assertTrue(text.contains("code=1"), text)
            assertTrue(vm.snapshot().state.isActive, vm.snapshot().state.toString())
        }
    }

    @Test
    fun firmwareReportsBootCompileErrorAndStaysActive() {
        runtimeTestWorkspace("firmware-invalid-boot") { workspace ->
            workspace.writeProgram(1, "boot.ck", "fun main() {}")
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
                                val code: Int = process::run("boot.ck")
                                terminal::println("code=" + code)
                                while true { sleep(20L) }
                            }
                            """.trimIndent(),
                        ),
                )

            vm.boot()
            runVmTicks(vm)

            val text = vm.forceScreenSnapshot().visibleText()
            assertTrue(text.contains("Compilation Error in boot.ck"), text)
            assertTrue(text.contains("pub fun main"), text)
            assertTrue(text.contains("code=1"), text)
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
                    terminalWidth = 16,
                    terminalHeight = 8,
                    colorTerminal = true,
                    allowedCapabilities = setOf(DeviceCapability.TERMINAL, DeviceCapability.SYSTEM),
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

            vm.boot()

            val terminalState =
                runBlocking {
                    val terminalState =
                        async {
                            withTimeout(5_000) {
                                vm.terminalStates.first()
                            }
                        }

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
                    terminalWidth = 16,
                    terminalHeight = 8,
                    colorTerminal = true,
                    allowedCapabilities = setOf(DeviceCapability.TERMINAL, DeviceCapability.SYSTEM),
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

            vm.boot()

            val terminalState =
                runBlocking {
                    val terminalState =
                        async {
                            withTimeout(5_000) {
                                vm.terminalStates.first()
                            }
                        }

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
