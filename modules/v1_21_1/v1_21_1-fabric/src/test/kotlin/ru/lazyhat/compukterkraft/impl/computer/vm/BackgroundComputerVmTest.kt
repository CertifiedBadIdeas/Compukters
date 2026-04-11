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
import ru.lazyhat.compukterkraft.core.computer.vm.ComputerWorkspaceHost
import ru.lazyhat.compukterkraft.lang.runtime.ComputerCapability
import ru.lazyhat.compukterkraft.lang.runtime.ComputerCpuResources
import ru.lazyhat.compukterkraft.lang.runtime.ComputerMemoryResources
import ru.lazyhat.compukterkraft.lang.runtime.ComputerProfile
import ru.lazyhat.compukterkraft.lang.runtime.ComputerQueueResources
import ru.lazyhat.compukterkraft.lang.runtime.ComputerResources
import ru.lazyhat.compukterkraft.lang.runtime.ComputerStorageResources
import ru.lazyhat.compukterkraft.lang.runtime.VmState
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
