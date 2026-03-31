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

package ck.mod.computer.vm

import ck.lang.runtime.ComputerCapability
import ck.lang.runtime.ComputerCpuResources
import ck.lang.runtime.ComputerMemoryResources
import ck.lang.runtime.ComputerProfile
import ck.lang.runtime.ComputerQueueResources
import ck.lang.runtime.ComputerResources
import ck.lang.runtime.ComputerStorageResources
import ck.lang.runtime.VmState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

class BackgroundComputerVmTest {
    @Test
    fun surfacesRomLimitFailureAsCrashedState() {
        val root = createTempDirectory("compukterkraft-background-vm")

        try {
            val workspace = ComputerWorkspaceHost(root)
            workspace.writeDocument(1, "bios.ck", "fun main() { }")

            val profile =
                ComputerProfile(
                    id = "tiny-rom",
                    displayName = "Tiny ROM",
                    cpuBudgetNanosPerSlice = 1_000_000,
                    maxEventQueueSize = 16,
                    terminalWidth = 16,
                    terminalHeight = 8,
                    colorTerminal = true,
                    allowedCapabilities = setOf(ComputerCapability.TERMINAL, ComputerCapability.SYSTEM),
                    resources =
                        ComputerResources(
                            cpu = ComputerCpuResources(wallTimeGuardNanosPerSlice = 1_000_000),
                            memory = ComputerMemoryResources(),
                            storage = ComputerStorageResources(programRomBytes = 1, diskBytes = 1024),
                            queues = ComputerQueueResources(eventQueueSlots = 16, hostCallQueueSlots = 16),
                        ),
                )

            val vm =
                BackgroundComputerVm(
                    computerId = 1,
                    profile = profile,
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = ComputerVmLogger { },
                    workspace = workspace,
                )

            vm.boot()

            val terminalState =
                runBlocking {
                    withTimeout(5_000) {
                        vm.terminalStates.first()
                    }
                }

            assertTrue(terminalState is VmState.Crashed)
            assertTrue(terminalState.errorMessage?.contains("ROM limit") == true)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}