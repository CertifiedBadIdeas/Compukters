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
package ru.lazyhat.compukterkraft.core.application.runtime

import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.HostCall
import ru.lazyhat.compukterkraft.lang.runtime.HostResult

/**
 * Dispatches [HostCall]s from the VM to the appropriate server-side handler.
 *
 * Terminal I/O is no longer routed through HostCalls — the VM writes directly
 * to its [ScreenBuffer]. Only filesystem operations remain.
 */
class HostCallDispatcher(
    private val computerId: Int,
    private val workspace: ComputerWorkspace,
) {
    fun dispatch(call: HostCall): HostResult =
        try {
            when (call) {
                is HostCall.FileExists -> {
                    HostResult.Success(
                        call.id,
                        workspace.readDocument(computerId, call.path) != null || workspace.isDirectory(computerId, call.path),
                    )
                }

                is HostCall.FileIsDirectory -> {
                    HostResult.Success(call.id, workspace.isDirectory(computerId, call.path))
                }

                is HostCall.FileReadText -> {
                    HostResult.Success(call.id, workspace.readDocument(computerId, call.path)?.text)
                }

                is HostCall.FileWriteText -> {
                    workspace.writeDocument(computerId, call.path, call.text)
                    HostResult.Success(call.id)
                }

                is HostCall.FileMakeDirectory -> {
                    HostResult.Success(call.id, workspace.makeDirectory(computerId, call.path))
                }

                is HostCall.FileRemove -> {
                    HostResult.Success(call.id, workspace.deleteDocument(computerId, call.path))
                }

                is HostCall.FileList -> {
                    HostResult.Success(call.id, workspace.list(computerId, call.path))
                }
            }
        } catch (failure: Throwable) {
            HostResult.Failure(call.id, failure.message ?: failure.javaClass.simpleName)
        }
}
