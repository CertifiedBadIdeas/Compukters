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

package ru.lazyhat.compukterkraft.core.device.vm.api

import ru.lazyhat.compukterkraft.core.device.vm.VmContext
import ru.lazyhat.compukterkraft.core.device.vm.VmPathResolver
import ru.lazyhat.compukterkraft.core.device.vm.VmProcessManager
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProcessApi
import ru.lazyhat.compukterkraft.lang.runtime.DeviceTerminalApi

internal class VmProcessApi(
    private val ctx: VmContext,
    private val initialArgument: String,
    private val deviceId: Int,
    private val pathResolver: VmPathResolver,
    private val filesystemApi: VmFileSystemApi,
    private val processManager: VmProcessManager,
    private val terminal: DeviceTerminalApi,
) : DeviceProcessApi {
    override val argument: String = initialArgument
    override val workingDirectory: String get() = pathResolver.workingDirectory

    override suspend fun changeDirectory(path: String): Boolean {
        val resolved = ctx.resolvePath(path)
        return filesystemApi.isDirectory(resolved).also { isDir ->
            if (isDir) pathResolver.updateWorkingDirectory(resolved)
        }
    }

    override suspend fun run(
        path: String,
        argument: String,
    ): Int = wait(spawn(path, argument))

    override suspend fun spawn(
        path: String,
        argument: String,
    ): Int = processManager.spawn(path, argument, workingDirectory, terminal)

    override suspend fun wait(pid: Int): Int = processManager.wait(pid)
}
