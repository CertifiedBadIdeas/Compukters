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
package ru.lazyhat.compukterkraft.common.infrastructure.workbench

import kotlinx.coroutines.flow.StateFlow
import ru.lazyhat.compukterkraft.common.menu.AbstractComputerMenu
import ru.lazyhat.compukterkraft.common.network.ClientNetworking
import ru.lazyhat.compukterkraft.common.network.server.ComputerWorkspaceServerMessage
import ru.lazyhat.compukterkraft.core.application.input.ComputerControlAction
import ru.lazyhat.compukterkraft.core.application.input.ControlInputEvent
import ru.lazyhat.compukterkraft.core.application.input.InputEventSink
import ru.lazyhat.compukterkraft.core.application.workbench.ComputerControlGateway
import ru.lazyhat.compukterkraft.core.application.workbench.WorkbenchIdeFacade
import ru.lazyhat.compukterkraft.core.application.workbench.WorkbenchRemoteState
import ru.lazyhat.compukterkraft.core.application.workbench.WorkbenchUpdateSource
import ru.lazyhat.compukterkraft.core.application.workbench.WorkspaceGateway
import ru.lazyhat.compukterkraft.core.language.LanguageServices
import ru.lazyhat.compukterkraft.lang.frontend.AnalyzedProgram
import ru.lazyhat.compukterkraft.lang.runtime.CompletionItem
import ru.lazyhat.compukterkraft.lang.runtime.ComputerIdeSnapshot
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspaceDocument
import ru.lazyhat.compukterkraft.lang.runtime.DefinitionTarget
import ru.lazyhat.compukterkraft.lang.runtime.HoverInfo

/**
 * Adapts [AbstractComputerMenu.workspaceStateFlow] to the [WorkbenchUpdateSource] interface.
 */
class MenuWorkspaceUpdateSource(
    private val menu: AbstractComputerMenu,
) : WorkbenchUpdateSource {
    override val stateFlow: StateFlow<WorkbenchRemoteState> = menu.workspaceStateFlow
}

class NetworkWorkspaceGateway(
    private val menu: AbstractComputerMenu,
) : WorkspaceGateway {
    override fun list(path: String) {
        ClientNetworking.sendToServer(
            ComputerWorkspaceServerMessage(
                menu,
                ComputerWorkspaceServerMessage.Action.LIST,
                path,
            ),
        )
    }

    override fun read(path: String) {
        ClientNetworking.sendToServer(
            ComputerWorkspaceServerMessage(
                menu,
                ComputerWorkspaceServerMessage.Action.READ,
                path,
            ),
        )
    }

    override fun write(
        path: String,
        text: String,
    ) {
        ClientNetworking.sendToServer(
            ComputerWorkspaceServerMessage(
                menu,
                ComputerWorkspaceServerMessage.Action.WRITE,
                path,
                text,
            ),
        )
    }
}

class InputHandlerControlGateway(
    private val inputEventSink: InputEventSink,
) : ComputerControlGateway {
    override fun reboot() {
        inputEventSink.accept(ControlInputEvent(ComputerControlAction.REBOOT))
    }
}

object LanguageWorkbenchIdeFacade : WorkbenchIdeFacade {
    private val ide = LanguageServices.ide

    private var lastAnalysisPath: String? = null
    private var lastAnalysisSource: String? = null
    private var lastAnalysis: AnalyzedProgram? = null

    override fun analyze(
        path: String,
        source: String,
    ): ComputerIdeSnapshot =
        ide.analyze(path, source).let { snapshot ->
            lastAnalysisPath = path
            lastAnalysisSource = source
            lastAnalysis = snapshot.analysis
            ComputerIdeSnapshot(
                ComputerWorkspaceDocument(path = path, text = source, version = 0L),
                snapshot.diagnostics,
                snapshot.highlights,
            )
        }

    override fun complete(
        path: String,
        source: String,
        line: Int,
        column: Int,
    ): List<CompletionItem> = ide.complete(path, source, line, column)

    override fun completeFromLastAnalysis(
        path: String,
        source: String,
        line: Int,
        column: Int,
    ): List<CompletionItem> {
        val cached = lastAnalysis
        if (cached != null && lastAnalysisPath == path && lastAnalysisSource == source) {
            return ide.completeFromAnalysis(cached, source, line, column)
        }
        return ide.complete(path, source, line, column)
    }

    override fun hover(
        path: String,
        source: String,
        line: Int,
        column: Int,
    ): HoverInfo? = ide.hover(path, source, line, column)

    override fun definition(
        path: String,
        source: String,
        line: Int,
        column: Int,
    ): DefinitionTarget? = ide.definition(path, source, line, column)
}
