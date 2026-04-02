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
package ck.mod.computer.vm

import ck.lang.runtime.ComputerCompletionRequest
import ck.lang.runtime.ComputerCompletionResponse
import ck.lang.runtime.ComputerDefinitionRequest
import ck.lang.runtime.ComputerDefinitionResponse
import ck.lang.runtime.ComputerHoverRequest
import ck.lang.runtime.ComputerHoverResponse
import ck.lang.runtime.ComputerIdeHost
import ck.lang.runtime.ComputerIdeSnapshot
import ck.lang.runtime.ComputerWorkspace
import ck.mod.language.LanguageServices

class WorkspaceComputerIdeHost(
    private val workspace: ComputerWorkspace,
) : ComputerIdeHost {
    override fun snapshot(
        computerId: Int,
        path: String,
    ): ComputerIdeSnapshot? {
        val document = workspace.readDocument(computerId, path) ?: return null
        val snapshot = LanguageServices.ide.analyze(document.path, document.text)
        return ComputerIdeSnapshot(
            document = document,
            diagnostics = snapshot.diagnostics,
            highlights = snapshot.highlights,
        )
    }

    override fun complete(
        computerId: Int,
        request: ComputerCompletionRequest,
    ): ComputerCompletionResponse {
        val document = workspace.readDocument(computerId, request.path)
        val items =
            if (document == null) {
                emptyList()
            } else {
                LanguageServices.ide.complete(document.path, document.text, request.line, request.column)
            }
        return ComputerCompletionResponse(items)
    }

    override fun hover(
        computerId: Int,
        request: ComputerHoverRequest,
    ): ComputerHoverResponse {
        val document = workspace.readDocument(computerId, request.path)
        val info =
            if (document == null) {
                null
            } else {
                LanguageServices.ide.hover(document.path, document.text, request.line, request.column)
            }
        return ComputerHoverResponse(info)
    }

    override fun definition(
        computerId: Int,
        request: ComputerDefinitionRequest,
    ): ComputerDefinitionResponse {
        val document = workspace.readDocument(computerId, request.path)
        val target =
            if (document == null) {
                null
            } else {
                LanguageServices.ide.definition(document.path, document.text, request.line, request.column)
            }
        return ComputerDefinitionResponse(target)
    }
}
