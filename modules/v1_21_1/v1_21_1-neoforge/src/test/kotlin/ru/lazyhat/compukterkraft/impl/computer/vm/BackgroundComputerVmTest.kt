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

package ru.lazyhat.compukterkraft.impl.computer.vm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.lazyhat.compukterkraft.core.computer.vm.BackgroundComputerVm
import ru.lazyhat.compukterkraft.core.computer.vm.ComputerVmLogger
import ru.lazyhat.compukterkraft.core.computer.vm.DeviceWorkspaceHost
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCapability
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCpuResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceMemoryResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProfile
import ru.lazyhat.compukterkraft.lang.runtime.DeviceQueueResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceStorageResources
import ru.lazyhat.compukterkraft.lang.runtime.VmState
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

class BackgroundComputerVmTest {
    @Test
    fun surfacesRomLimitFailureAsCrashedState() {
        val root = createTempDirectory("compukterkraft-background-vm")

        try {
            val workspace = DeviceWorkspaceHost(root)
            workspace.writeDocument(1, "bios.ck", "fun main() { }")

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
                BackgroundComputerVm(
                    deviceId = 1,
                    profile = profile,
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = ComputerVmLogger { },
                    workspace = workspace,
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
}
