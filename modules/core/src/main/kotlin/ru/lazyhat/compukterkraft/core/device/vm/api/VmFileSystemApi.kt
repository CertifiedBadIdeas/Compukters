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

import ru.lazyhat.compukterkraft.core.device.vm.VmPathResolver
import ru.lazyhat.compukterkraft.lang.runtime.DeviceFileSystemApi
import ru.lazyhat.compukterkraft.lang.runtime.DeviceWorkspaceEntry

internal class VmFileSystemApi(
    private val pathResolver: VmPathResolver = VmPathResolver(),
) : DeviceFileSystemApi {
    private fun disabled(path: String): Nothing =
        error("Kotlin filesystem API is disabled for the native daemon VM: ${pathResolver.resolve(path)}")

    override suspend fun exists(path: String): Boolean = disabled(path)

    override suspend fun isDirectory(path: String): Boolean = disabled(path)

    override suspend fun readText(path: String): String? = disabled(path)

    override suspend fun writeText(
        path: String,
        text: String,
    ) = disabled(path)

    override suspend fun makeDirectory(path: String): Boolean = disabled(path)

    override suspend fun remove(path: String): Boolean = disabled(path)

    override suspend fun list(path: String): List<DeviceWorkspaceEntry> = disabled(path)
}
