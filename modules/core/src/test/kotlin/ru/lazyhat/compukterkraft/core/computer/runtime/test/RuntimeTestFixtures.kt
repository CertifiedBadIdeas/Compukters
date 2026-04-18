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
package ru.lazyhat.compukterkraft.core.computer.runtime.test

import ru.lazyhat.compukterkraft.core.computer.vm.ComputerWorkspaceHost
import ru.lazyhat.compukterkraft.lang.runtime.ComputerCapability
import ru.lazyhat.compukterkraft.lang.runtime.ComputerCpuResources
import ru.lazyhat.compukterkraft.lang.runtime.ComputerMemoryResources
import ru.lazyhat.compukterkraft.lang.runtime.ComputerProfile
import ru.lazyhat.compukterkraft.lang.runtime.ComputerQueueResources
import ru.lazyhat.compukterkraft.lang.runtime.ComputerResources
import ru.lazyhat.compukterkraft.lang.runtime.ComputerStorageResources
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class RuntimeTestWorkspace(
    val root: Path,
    val host: ComputerWorkspaceHost,
) {
    fun writeProgram(
        computerId: Int,
        path: String,
        source: String,
    ) {
        val computerRoot = root.resolve(computerId.toString()).createDirectories()
        val file = computerRoot.resolve(path.trimStart('/')).normalize()
        require(file.startsWith(computerRoot)) {
            "Program path must stay inside the test workspace: $path"
        }
        file.parent?.createDirectories()
        file.writeText(source)
    }
}

fun runtimeProfile(
    id: String = "test",
    displayName: String = "Test",
    programRomBytes: Long = 4096,
    diskBytes: Long = 1024,
    allowedCapabilities: Set<ComputerCapability> =
        setOf(
            ComputerCapability.TERMINAL,
            ComputerCapability.FILESYSTEM,
            ComputerCapability.EVENTS,
            ComputerCapability.SYSTEM,
            ComputerCapability.IDE,
        ),
): ComputerProfile =
    ComputerProfile(
        id = id,
        displayName = displayName,
        cpuBudgetNanosPerSlice = 1_000_000,
        maxEventQueueSize = 16,
        terminalWidth = 16,
        terminalHeight = 8,
        colorTerminal = true,
        allowedCapabilities = allowedCapabilities,
        resources =
            ComputerResources(
                cpu = ComputerCpuResources(wallTimeGuardNanosPerSlice = 1_000_000),
                memory = ComputerMemoryResources(),
                storage = ComputerStorageResources(programRomBytes = programRomBytes, diskBytes = diskBytes),
                queues = ComputerQueueResources(eventQueueSlots = 16, hostCallQueueSlots = 16),
            ),
    )

inline fun runtimeTestWorkspace(
    name: String,
    block: (RuntimeTestWorkspace) -> Unit,
) {
    val root = createTempDirectory("compukterkraft-$name")
    try {
        block(RuntimeTestWorkspace(root = root, host = ComputerWorkspaceHost(rootPath = root)))
    } finally {
        root.toFile().deleteRecursively()
    }
}
