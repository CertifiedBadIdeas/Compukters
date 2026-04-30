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

import ru.lazyhat.compukterkraft.core.language.LanguageServices
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCompletionRequest
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCompletionResponse
import ru.lazyhat.compukterkraft.lang.runtime.DeviceDefinitionRequest
import ru.lazyhat.compukterkraft.lang.runtime.DeviceDefinitionResponse
import ru.lazyhat.compukterkraft.lang.runtime.DeviceHoverRequest
import ru.lazyhat.compukterkraft.lang.runtime.DeviceHoverResponse
import ru.lazyhat.compukterkraft.lang.runtime.DeviceIdeHost
import ru.lazyhat.compukterkraft.lang.runtime.DeviceIdeSnapshot
import ru.lazyhat.compukterkraft.lang.runtime.DeviceWorkspace

class WorkspaceComputerIdeHost(
    private val workspace: DeviceWorkspace,
) : DeviceIdeHost {
    override fun snapshot(
        computerId: Int,
        path: String,
    ): DeviceIdeSnapshot? {
        val document = workspace.readDocument(computerId, path) ?: return null
        val snapshot = LanguageServices.ide.analyze(document.path, document.text)
        return DeviceIdeSnapshot(
            document = document,
            diagnostics = snapshot.diagnostics,
            highlights = snapshot.highlights,
        )
    }

    override fun complete(
        computerId: Int,
        request: DeviceCompletionRequest,
    ): DeviceCompletionResponse {
        val document = workspace.readDocument(computerId, request.path)
        val items =
            if (document == null) {
                emptyList()
            } else {
                LanguageServices.ide.complete(document.path, document.text, request.line, request.column)
            }
        return DeviceCompletionResponse(items)
    }

    override fun hover(
        computerId: Int,
        request: DeviceHoverRequest,
    ): DeviceHoverResponse {
        val document = workspace.readDocument(computerId, request.path)
        val info =
            if (document == null) {
                null
            } else {
                LanguageServices.ide.hover(document.path, document.text, request.line, request.column)
            }
        return DeviceHoverResponse(info)
    }

    override fun definition(
        computerId: Int,
        request: DeviceDefinitionRequest,
    ): DeviceDefinitionResponse {
        val document = workspace.readDocument(computerId, request.path)
        val target =
            if (document == null) {
                null
            } else {
                LanguageServices.ide.definition(document.path, document.text, request.line, request.column)
            }
        return DeviceDefinitionResponse(target)
    }
}
