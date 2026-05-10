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
import ru.lazyhat.compukterkraft.lang.runtime.DeviceFileSystemApi
import ru.lazyhat.compukterkraft.lang.runtime.DeviceWorkspaceEntry
import ru.lazyhat.compukterkraft.lang.runtime.HostCall

internal class VmFileSystemApi(
    private val ctx: VmContext,
    private val pathResolver: VmPathResolver = VmPathResolver(),
) : DeviceFileSystemApi {
    private fun resolve(path: String): String = pathResolver.resolve(path)

    override suspend fun exists(path: String): Boolean = ctx.awaitHostCall { HostCall.FileExists(it, resolve(path)) }

    override suspend fun isDirectory(path: String): Boolean = ctx.awaitHostCall { HostCall.FileIsDirectory(it, resolve(path)) }

    override suspend fun readText(path: String): String? = ctx.awaitHostCall { HostCall.FileReadText(it, resolve(path)) }

    override suspend fun writeText(
        path: String,
        text: String,
    ) {
        ctx.awaitHostCall<Unit> { HostCall.FileWriteText(it, resolve(path), text) }
    }

    override suspend fun makeDirectory(path: String): Boolean = ctx.awaitHostCall { HostCall.FileMakeDirectory(it, resolve(path)) }

    override suspend fun remove(path: String): Boolean = ctx.awaitHostCall { HostCall.FileRemove(it, resolve(path)) }

    override suspend fun list(path: String): List<DeviceWorkspaceEntry> = ctx.awaitHostCall { HostCall.FileList(it, resolve(path)) }
}
