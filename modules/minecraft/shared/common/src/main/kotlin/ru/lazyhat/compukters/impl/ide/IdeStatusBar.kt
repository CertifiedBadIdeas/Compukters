/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.impl.ide

import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisState
import ru.lazyhat.compukters.ide.client.build.IdeBuildState
import ru.lazyhat.compukters.ide.client.files.IdeComputerTransferState
import ru.lazyhat.compukters.ide.client.state.IdeBusyOperation
import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.client.state.IdeToolingState
import ru.lazyhat.compukters.ide.client.state.IdeWorkspaceView
import ru.lazyhat.compukters.ide.client.target.IdeTargetState

/** Complete status projection; compact rendering never discards the underlying messages. */
internal object IdeStatusBar {
    fun messages(
        workspace: IdeWorkspaceView,
        targetState: IdeTargetState,
        toolingState: IdeToolingState,
        busy: Set<IdeBusyOperation>,
    ): List<String> {
        val parts = linkedSetOf<String>()
        val errors = workspace.diagnostics.errors
        val warnings = workspace.diagnostics.warnings
        parts += "$errors ${if (errors == 1) "error" else "errors"} · $warnings ${if (warnings == 1) "warning" else "warnings"}"
        (workspace.editor as? IdeEditorView.Text)?.let { editor ->
            parts +=
                if (editor.conflict) {
                    "Conflict"
                } else if (editor.dirty) {
                    "Modified"
                } else {
                    "Saved"
                }
            parts += "UTF-16 ${editor.caretUtf16}"
            when (val analysis = editor.analysis) {
                is IdeAnalysisState.Loading -> parts += "Analysis…"
                is IdeAnalysisState.Unavailable -> parts += analysis.status
                else -> Unit
            }
        }
        when (val build = workspace.build) {
            is IdeBuildState.Succeeded -> parts += "Artifact ${build.bytes} B · ${if (build.cacheHit) "cache hit" else "compiled"}"
            is IdeBuildState.Failed -> parts += build.detail
            is IdeBuildState.Compiling -> parts += "Building…"
            is IdeBuildState.Saving -> parts += "Saving…"
            else -> Unit
        }
        if (IdeBusyOperation.Format in busy) parts += "Formatting…"
        targetStatus(targetState)?.let(parts::add)
        when (toolingState) {
            IdeToolingState.Preparing -> parts += "Kotlin tooling is starting…"
            is IdeToolingState.Unavailable -> parts += toolingState.detail
            IdeToolingState.Ready -> Unit
        }
        workspace.status?.let { parts += it.message }
        when (val transfer = workspace.computerTransfer) {
            IdeComputerTransferState.Idle -> {
                Unit
            }

            is IdeComputerTransferState.Downloading -> {
                parts +=
                    "Copying ${transfer.progress.filesComplete}/${transfer.progress.filesTotal} · ${transfer.progress.bytesComplete}/${transfer.progress.bytesTotal} B"
            }

            is IdeComputerTransferState.ConfirmationRequired -> {
                parts += "Copy ready · confirmation required"
            }

            is IdeComputerTransferState.Failed -> {
                parts += transfer.detail
            }
        }
        return parts.toList()
    }

    fun draw(
        output: IdeDrawBuilder,
        geometry: IdeRenderGeometry,
        workspace: IdeWorkspaceView,
        target: IdeTargetState,
        tooling: IdeToolingState,
        busy: Set<IdeBusyOperation>,
    ) {
        val bounds = geometry.status
        val details = IdeRect(bounds.right - 58, bounds.top + 1, bounds.right - 4, bounds.bottom - 1)
        output.target(IdeHitAction.StatusDetails, details, true, "Full status details", style = IdeControlStyle.Neutral)
        output.ui(IdeTextKind.Status, "Details", details.left + 6, geometry.font.centeredCellTop(details), IdeColors.MUTED, details)
        val editor = workspace.editor as? IdeEditorView.Text
        val metadata = messages(workspace, target, tooling, busy).take(if (editor == null) 1 else 2).joinToString(" · ")
        val available = (details.left - bounds.left - 14).coerceAtLeast(0)
        val metadataWidth = minOf(output.uiWidth(metadata), available / 2)
        val metadataBounds = IdeRect(bounds.left + 6, bounds.top, bounds.left + 6 + metadataWidth, bounds.bottom)
        output.ui(
            IdeTextKind.Status,
            output.fitUi(metadata, metadataWidth),
            metadataBounds.left,
            geometry.font.centeredCellTop(bounds),
            IdeColors.MUTED,
            metadataBounds,
        )
        val activityBounds = IdeRect(metadataBounds.right + 8, bounds.top, details.left - 4, bounds.bottom)
        val activity = output.fitUi(activity(workspace, target, tooling, busy), activityBounds.width)
        output.ui(
            IdeTextKind.Status,
            activity,
            activityBounds.right - output.uiWidth(activity),
            geometry.font.centeredCellTop(bounds),
            IdeColors.MUTED,
            activityBounds,
        )
    }

    private fun activity(
        workspace: IdeWorkspaceView,
        target: IdeTargetState,
        tooling: IdeToolingState,
        busy: Set<IdeBusyOperation>,
    ): String =
        when {
            workspace.build is IdeBuildState.Compiling -> {
                "Building…"
            }

            workspace.build is IdeBuildState.Saving -> {
                "Saving…"
            }

            IdeBusyOperation.Format in busy -> {
                "Formatting…"
            }

            workspace.computerTransfer is IdeComputerTransferState.Downloading -> {
                "Copying files…"
            }

            workspace.computerTransfer is IdeComputerTransferState.Failed -> {
                "Copy failed"
            }

            target is IdeTargetState.Failed || target is IdeTargetState.Detached -> {
                "Target unavailable"
            }

            tooling is IdeToolingState.Preparing -> {
                "Kotlin tooling is starting…"
            }

            tooling is IdeToolingState.Unavailable -> {
                "Kotlin tooling unavailable"
            }

            workspace.build is IdeBuildState.Failed -> {
                "Build failed"
            }

            (workspace.editor as? IdeEditorView.Text)?.analysis is IdeAnalysisState.Loading -> {
                "Analysis…"
            }

            (workspace.editor as? IdeEditorView.Text)?.analysis is IdeAnalysisState.Unavailable -> {
                "Analysis unavailable"
            }

            workspace.status != null -> {
                checkNotNull(workspace.status)
                    .message
                    .lineSequence()
                    .firstOrNull()
                    .orEmpty()
            }

            targetStatus(target) != null -> {
                checkNotNull(targetStatus(target))
            }

            workspace.build is IdeBuildState.Succeeded -> {
                val build = workspace.build as IdeBuildState.Succeeded
                "Artifact ${build.bytes} B · ${if (build.cacheHit) "cache hit" else "compiled"}"
            }

            else -> {
                "Ready"
            }
        }
}
