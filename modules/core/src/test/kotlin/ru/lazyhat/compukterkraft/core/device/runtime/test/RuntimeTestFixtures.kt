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
package ru.lazyhat.compukterkraft.core.device.runtime.test

import ru.lazyhat.compukterkraft.core.device.vm.DeviceWorkspaceHost
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCapability
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCpuResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceMemoryResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProfile
import ru.lazyhat.compukterkraft.lang.runtime.DeviceQueueResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceStorageResources
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class RuntimeTestWorkspace(
    val root: Path,
    val host: DeviceWorkspaceHost,
) {
    fun writeProgram(
        deviceId: Int,
        path: String,
        source: String,
    ) {
        val computerRoot = root.resolve(deviceId.toString()).createDirectories()
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
    allowedCapabilities: Set<DeviceCapability> =
        setOf(
            DeviceCapability.TERMINAL,
            DeviceCapability.FILESYSTEM,
            DeviceCapability.EVENTS,
            DeviceCapability.SYSTEM,
            DeviceCapability.IDE,
        ),
): DeviceProfile =
    DeviceProfile(
        id = id,
        displayName = displayName,
        cpuBudgetNanosPerSlice = 1_000_000,
        maxEventQueueSize = 16,
        terminalWidth = 16,
        terminalHeight = 8,
        colorTerminal = true,
        allowedCapabilities = allowedCapabilities,
        resources =
            DeviceResources(
                cpu = DeviceCpuResources(wallTimeGuardNanosPerSlice = 1_000_000),
                memory = DeviceMemoryResources(),
                storage = DeviceStorageResources(programRomBytes = programRomBytes, diskBytes = diskBytes),
                queues = DeviceQueueResources(eventQueueSlots = 16, hostCallQueueSlots = 16),
            ),
    )

inline fun runtimeTestWorkspace(
    name: String,
    block: (RuntimeTestWorkspace) -> Unit,
) {
    val root = createTempDirectory("compukterkraft-$name")
    try {
        block(RuntimeTestWorkspace(root = root, host = DeviceWorkspaceHost(rootPath = root)))
    } finally {
        root.toFile().deleteRecursively()
    }
}
