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

interface ComputerProgram {
    suspend fun run(runtime: ComputerRuntime)
}

interface ComputerRuntime {
    val profile: ComputerProfile
    val system: ComputerSystemApi
    val terminal: ComputerTerminalApi
    val stdio: ComputerStdioApi
    val filesystem: ComputerFileSystemApi
    val process: ComputerProcessApi
    val redstone: ComputerRedstoneApi
    val peripherals: ComputerPeripheralApi

    suspend fun pullEvent(filter: String? = null): VmEvent

    suspend fun sleep(ticks: Long)

    suspend fun yield()
}

interface ComputerSystemApi {
    val computerId: Int
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

interface ComputerTerminalApi {
    fun write(text: String)

    fun printLine(text: String)

    suspend fun readLine(prompt: String = ""): String

    fun clear()

    fun setCursor(
        x: Int,
        y: Int,
    )
}

interface ComputerFileSystemApi {
    suspend fun exists(path: String): Boolean

    suspend fun isDirectory(path: String): Boolean

    suspend fun readText(path: String): String?

    suspend fun writeText(
        path: String,
        text: String,
    )

    suspend fun makeDirectory(path: String): Boolean

    suspend fun remove(path: String): Boolean

    suspend fun list(path: String = ""): List<ComputerWorkspaceEntry>
}

interface ComputerProcessApi {
    val workingDirectory: String
    val argument: String

    suspend fun changeDirectory(path: String): Boolean

    suspend fun run(path: String): Int = run(path, "")

    suspend fun run(
        path: String,
        argument: String,
    ): Int
}

interface ComputerRedstoneApi

interface ComputerPeripheralApi {
    fun monitorExists(): Boolean = false
}

object ComputerProgramFiles {
    const val FILE_EXTENSION = ".ck"
    const val BIOS_SCRIPT_NAME = "bios.ck"
}
