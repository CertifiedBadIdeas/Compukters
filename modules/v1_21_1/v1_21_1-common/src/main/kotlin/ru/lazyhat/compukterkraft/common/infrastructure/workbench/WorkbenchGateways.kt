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
import ru.lazyhat.compukterkraft.common.network.ClientNetworking
import ru.lazyhat.compukterkraft.common.workbench.menu.AbstractWorkbenchMenu
import ru.lazyhat.compukterkraft.common.workbench.network.server.WorkbenchWorkspaceServerMessage
import ru.lazyhat.compukterkraft.core.block.ComputerFamily
import ru.lazyhat.compukterkraft.core.computer.input.ComputerControlAction
import ru.lazyhat.compukterkraft.core.computer.input.ControlInputEvent
import ru.lazyhat.compukterkraft.core.computer.input.InputEventSink
import ru.lazyhat.compukterkraft.core.computer.vm.ComputerProfileRegistry
import ru.lazyhat.compukterkraft.core.computer.workbench.ComputerControlGateway
import ru.lazyhat.compukterkraft.core.computer.workbench.IdeRuntimeCatalogSource
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchIdeFacade
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchRemoteState
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchTargetState
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchUpdateSource
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkspaceGateway
import ru.lazyhat.compukterkraft.lang.api.BuiltinRegistry
import ru.lazyhat.compukterkraft.lang.frontend.AnalyzedProgram
import ru.lazyhat.compukterkraft.lang.frontend.LanguageBuiltins
import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import ru.lazyhat.compukterkraft.lang.frontend.LanguageIde
import ru.lazyhat.compukterkraft.lang.runtime.CompletionItem
import ru.lazyhat.compukterkraft.lang.runtime.CompletionItemKind
import ru.lazyhat.compukterkraft.lang.runtime.ComputerCapability
import ru.lazyhat.compukterkraft.lang.runtime.ComputerIdeSnapshot
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspaceDocument
import ru.lazyhat.compukterkraft.lang.runtime.DefinitionTarget
import ru.lazyhat.compukterkraft.lang.runtime.HoverInfo

/** Adapts menu-owned Workbench remote state to the [WorkbenchUpdateSource] interface. */
class MenuWorkspaceUpdateSource(
    private val remoteStateFlow: StateFlow<WorkbenchRemoteState>,
) : WorkbenchUpdateSource {
    override val stateFlow: StateFlow<WorkbenchRemoteState> = remoteStateFlow
}

class NetworkWorkbenchWorkspaceGateway(
    private val menu: AbstractWorkbenchMenu,
) : WorkspaceGateway {
    override fun list(path: String) {
        ClientNetworking.sendToServer(
            WorkbenchWorkspaceServerMessage(
                menu,
                WorkbenchWorkspaceServerMessage.Action.LIST,
                path,
            ),
        )
    }

    override fun read(path: String) {
        ClientNetworking.sendToServer(
            WorkbenchWorkspaceServerMessage(
                menu,
                WorkbenchWorkspaceServerMessage.Action.READ,
                path,
            ),
        )
    }
}

class NetworkWorkbenchOpsGateway(
    private val menu: AbstractWorkbenchMenu,
) : ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchOpsGateway {
    override fun sendOps(
        path: String,
        ops: List<ru.lazyhat.compukterkraft.core.computer.workbench.crdt.Op>,
    ) {
        ClientNetworking.sendToServer(
            ru.lazyhat.compukterkraft.common.workbench.network.server.WorkbenchOpsServerMessage(
                containerId = menu.containerId,
                path = path,
                ops = ops,
            ),
        )
    }

    override fun sessionOpen(path: String) {
        // The READ action implicitly opens the server session today; the dedicated open
        // message will be added if/when phase-2 multi-session support lands.
    }

    override fun sendCursor(
        path: String,
        cursor: ru.lazyhat.compukterkraft.core.computer.workbench.crdt.CursorAnchor?,
    ) {
        ClientNetworking.sendToServer(
            ru.lazyhat.compukterkraft.common.workbench.network.server.WorkbenchCursorServerMessage(
                containerId = menu.containerId,
                path = path,
                cursor = cursor,
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

    override fun runTargetProgram() {
    }

    override fun attachTargetTerminal() {
    }
}

class NetworkWorkbenchControlGateway(
    private val menu: AbstractWorkbenchMenu,
) : ComputerControlGateway {
    override fun reboot() {
        ClientNetworking.sendToServer(WorkbenchWorkspaceServerMessage(menu, WorkbenchWorkspaceServerMessage.Action.REBOOT))
    }

    override fun runTargetProgram() {
        ClientNetworking.sendToServer(WorkbenchWorkspaceServerMessage(menu, WorkbenchWorkspaceServerMessage.Action.RUN))
    }

    override fun attachTargetTerminal() {
        ClientNetworking.sendToServer(WorkbenchWorkspaceServerMessage(menu, WorkbenchWorkspaceServerMessage.Action.ATTACH_TERMINAL))
    }
}

class ComputerFamilyCatalogSource(
    private val family: ComputerFamily,
) : IdeRuntimeCatalogSource {
    override fun runtimeRegistry(): BuiltinRegistry {
        val profile = ComputerProfileRegistry.forFamily(family)
        val defaultRegistry = LanguageBuiltins.defaultRuntimeRegistry
        val modules =
            buildList {
                defaultRegistry.module("terminal")?.let(::add)
                defaultRegistry.module("system")?.let(::add)
                if (ComputerCapability.FILESYSTEM in profile.allowedCapabilities) {
                    defaultRegistry.module("filesystem")?.let(::add)
                }
                if (ComputerCapability.EVENTS in profile.allowedCapabilities) {
                    defaultRegistry.module("events")?.let(::add)
                }
                if (ComputerCapability.SYSTEM in profile.allowedCapabilities) {
                    defaultRegistry.module("process")?.let(::add)
                    defaultRegistry.module("strings")?.let(::add)
                }
            }

        return BuiltinRegistry(
            modules = modules,
            globals = defaultRegistry.globals,
            builtinTypes = defaultRegistry.builtinTypes,
        )
    }
}

class WorkbenchTargetCatalogSource(
    private val targetState: WorkbenchTargetState,
) : IdeRuntimeCatalogSource {
    override fun runtimeRegistry(): BuiltinRegistry {
        val family =
            targetState.familyId
                ?.let { familyId -> ComputerFamily.entries.firstOrNull { it.name.equals(familyId, ignoreCase = true) } }
                ?: ComputerFamily.NORMAL
        return ComputerFamilyCatalogSource(family).runtimeRegistry()
    }
}

class LanguageWorkbenchIdeFacade(
    catalogSource: IdeRuntimeCatalogSource,
) : WorkbenchIdeFacade {
    private val registry = catalogSource.runtimeRegistry()
    private val ide = LanguageIde(LanguageFrontend(registry), registry)

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

    override fun availableImports(
        path: String,
        source: String,
    ): List<CompletionItem> {
        val probeSource = if (source.contains("import ")) source else "$source\nimport "
        val probeLine = probeSource.lines().lastIndex
        val probeColumn = probeSource.lines().last().length
        return completeFromLastAnalysis(path, probeSource, probeLine, probeColumn)
            .filter { it.kind == CompletionItemKind.MODULE }
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
