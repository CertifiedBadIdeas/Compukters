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
package ru.lazyhat.compukterkraft.core.device.runtime

import ru.lazyhat.compukterkraft.lang.runtime.DeviceWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.HostCall
import ru.lazyhat.compukterkraft.lang.runtime.HostResult

/**
 * Dispatches [HostCall]s from the VM to the appropriate server-side handler.
 *
 * Display, events, process, and IPC are VM-local runtime APIs. Filesystem
 * operations remain host calls because they cross the workspace boundary.
 */
class HostCallDispatcher(
    private val deviceId: Int,
    private val workspace: DeviceWorkspace,
) {
    fun dispatch(call: HostCall): HostResult =
        try {
            when (call) {
                is HostCall.FileExists -> {
                    HostResult.Success(
                        call.id,
                        workspace.readDocument(deviceId, call.path) != null || workspace.isDirectory(deviceId, call.path),
                    )
                }

                is HostCall.FileIsDirectory -> {
                    HostResult.Success(call.id, workspace.isDirectory(deviceId, call.path))
                }

                is HostCall.FileReadText -> {
                    HostResult.Success(call.id, workspace.readDocument(deviceId, call.path)?.text)
                }

                is HostCall.FileWriteText -> {
                    workspace.writeDocument(deviceId, call.path, call.text)
                    HostResult.Success(call.id)
                }

                is HostCall.FileMakeDirectory -> {
                    HostResult.Success(call.id, workspace.makeDirectory(deviceId, call.path))
                }

                is HostCall.FileRemove -> {
                    HostResult.Success(call.id, workspace.deleteDocument(deviceId, call.path))
                }

                is HostCall.FileList -> {
                    HostResult.Success(call.id, workspace.list(deviceId, call.path))
                }
            }
        } catch (failure: Throwable) {
            HostResult.Failure(call.id, failure.message ?: failure.javaClass.simpleName)
        }
}
