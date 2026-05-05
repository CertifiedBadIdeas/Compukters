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

interface DeviceProgram {
    suspend fun run(runtime: DeviceRuntime)
}

interface DeviceRuntime {
    val profile: DeviceProfile
    val system: DeviceSystemApi
    val terminal: DeviceTerminalApi
    val stdio: DeviceStdioApi
    val filesystem: DeviceFileSystemApi
    val process: DeviceProcessApi
    val redstone: DeviceRedstoneApi
    val peripherals: DevicePeripheralApi

    suspend fun pullEvent(filter: String? = null): VmEvent

    suspend fun sleep(ticks: Long)

    suspend fun yield()
}

interface DeviceSystemApi {
    val deviceId: Int
    val label: String?
    val currentTick: Long

    fun queueEvent(
        name: String,
        arguments: List<Any?> = emptyList(),
    )

    fun shutdown()

    fun reboot()

    fun log(message: String)
}

interface DeviceTerminalApi {
    fun write(text: String)

    fun println(text: String)

    suspend fun readln(prompt: String = ""): String

    fun clear()

    fun setCursor(
        x: Int,
        y: Int,
    )
}

interface DeviceFileSystemApi {
    suspend fun exists(path: String): Boolean

    suspend fun isDirectory(path: String): Boolean

    suspend fun readText(path: String): String?

    suspend fun writeText(
        path: String,
        text: String,
    )

    suspend fun makeDirectory(path: String): Boolean

    suspend fun remove(path: String): Boolean

    suspend fun list(path: String = ""): List<DeviceWorkspaceEntry>
}

interface DeviceProcessApi {
    val workingDirectory: String
    val argument: String

    suspend fun changeDirectory(path: String): Boolean

    suspend fun run(path: String): Int = run(path, "")

    suspend fun run(
        path: String,
        argument: String,
    ): Int
}

interface DeviceRedstoneApi

interface DevicePeripheralApi {
    fun monitorExists(): Boolean = false
}

object DeviceProgramFiles {
    const val FILE_EXTENSION = ".ck"
    const val BIOS_SCRIPT_NAME = "bios.ck"
    const val BOOT_SCRIPT_NAME = "boot.ck"
}
