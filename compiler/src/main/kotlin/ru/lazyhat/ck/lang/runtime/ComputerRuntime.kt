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

package ru.lazyhat.ck.lang.runtime

interface ComputerProgram {
    suspend fun run(runtime: ComputerRuntime)
}

interface ComputerRuntime {
    val profile: ComputerProfile
    val system: ComputerSystemApi
    val terminal: ComputerTerminalApi
    val filesystem: ComputerFileSystemApi
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
    suspend fun write(text: String)

    suspend fun printLine(text: String)

    suspend fun clear()

    suspend fun setCursor(
        x: Int,
        y: Int,
    )
}

interface ComputerFileSystemApi {
    suspend fun exists(path: String): Boolean

    suspend fun readText(path: String): String?

    suspend fun writeText(
        path: String,
        text: String,
    )

    suspend fun list(path: String = ""): List<ComputerWorkspaceEntry>
}

interface ComputerRedstoneApi

interface ComputerPeripheralApi

object ComputerProgramFiles {
    const val FILE_EXTENSION = ".ck"
    const val BIOS_SCRIPT_NAME = "bios.ck"
}
