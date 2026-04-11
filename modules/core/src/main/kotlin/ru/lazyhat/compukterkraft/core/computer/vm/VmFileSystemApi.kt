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

package ru.lazyhat.compukterkraft.core.computer.vm

import ru.lazyhat.compukterkraft.lang.runtime.ComputerFileSystemApi
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspaceEntry
import ru.lazyhat.compukterkraft.lang.runtime.HostCall

class VmFileSystemApi(
    private val ctx: VmContext,
) : ComputerFileSystemApi {
    override suspend fun exists(path: String): Boolean = ctx.awaitHostCall { HostCall.FileExists(it, ctx.resolvePath(path)) }

    override suspend fun isDirectory(path: String): Boolean = ctx.awaitHostCall { HostCall.FileIsDirectory(it, ctx.resolvePath(path)) }

    override suspend fun readText(path: String): String? = ctx.awaitHostCall { HostCall.FileReadText(it, ctx.resolvePath(path)) }

    override suspend fun writeText(
        path: String,
        text: String,
    ) {
        ctx.awaitHostCall<Unit> { HostCall.FileWriteText(it, ctx.resolvePath(path), text) }
    }

    override suspend fun makeDirectory(path: String): Boolean = ctx.awaitHostCall { HostCall.FileMakeDirectory(it, ctx.resolvePath(path)) }

    override suspend fun remove(path: String): Boolean = ctx.awaitHostCall { HostCall.FileRemove(it, ctx.resolvePath(path)) }

    override suspend fun list(path: String): List<ComputerWorkspaceEntry> =
        ctx.awaitHostCall { HostCall.FileList(it, ctx.resolvePath(path)) }
}
