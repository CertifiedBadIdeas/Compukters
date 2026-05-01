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
package ru.lazyhat.compukterkraft.core.device.vm

import ru.lazyhat.compukterkraft.core.language.LanguageServices
import ru.lazyhat.compukterkraft.lang.frontend.LanguageIde
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCompletionRequest
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCompletionResponse
import ru.lazyhat.compukterkraft.lang.runtime.DeviceDefinitionRequest
import ru.lazyhat.compukterkraft.lang.runtime.DeviceDefinitionResponse
import ru.lazyhat.compukterkraft.lang.runtime.DeviceHoverRequest
import ru.lazyhat.compukterkraft.lang.runtime.DeviceHoverResponse
import ru.lazyhat.compukterkraft.lang.runtime.DeviceIdeHost
import ru.lazyhat.compukterkraft.lang.runtime.DeviceIdeSnapshot
import ru.lazyhat.compukterkraft.lang.runtime.DeviceWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.DeviceWorkspaceSourceLoader

class WorkspaceDeviceIdeHost(
    private val workspace: DeviceWorkspace,
) : DeviceIdeHost {
    override fun snapshot(
        deviceId: Int,
        path: String,
    ): DeviceIdeSnapshot? {
        val document = workspace.readDocument(deviceId, path) ?: return null
        val snapshot = ide(deviceId).analyze(document.path, document.text)
        return DeviceIdeSnapshot(
            document = document,
            diagnostics = snapshot.diagnostics,
            highlights = snapshot.highlights,
        )
    }

    override fun complete(
        deviceId: Int,
        request: DeviceCompletionRequest,
    ): DeviceCompletionResponse {
        val document = workspace.readDocument(deviceId, request.path)
        val items =
            if (document == null) {
                emptyList()
            } else {
                ide(deviceId).complete(document.path, document.text, request.line, request.column)
            }
        return DeviceCompletionResponse(items)
    }

    override fun hover(
        deviceId: Int,
        request: DeviceHoverRequest,
    ): DeviceHoverResponse {
        val document = workspace.readDocument(deviceId, request.path)
        val info =
            if (document == null) {
                null
            } else {
                ide(deviceId).hover(document.path, document.text, request.line, request.column)
            }
        return DeviceHoverResponse(info)
    }

    override fun definition(
        deviceId: Int,
        request: DeviceDefinitionRequest,
    ): DeviceDefinitionResponse {
        val document = workspace.readDocument(deviceId, request.path)
        val target =
            if (document == null) {
                null
            } else {
                ide(deviceId).definition(document.path, document.text, request.line, request.column)
            }
        return DeviceDefinitionResponse(target)
    }

    private fun ide(deviceId: Int): LanguageIde =
        LanguageIde(
            LanguageServices.frontend,
            LanguageServices.frontend.registry,
            sourceIndex = DeviceWorkspaceSourceLoader(workspace, deviceId),
        )
}
