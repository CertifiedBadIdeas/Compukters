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
import ru.lazyhat.compukters.ide.client.analysis.IdeSemanticInteraction
import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.client.state.IdePageState
import ru.lazyhat.compukters.ide.client.state.IdeViewState

/** One admitted view supplies drawing and interaction; never retained across events or ticks. */
internal class IdePresentationSnapshot(
    val state: IdeViewState,
    val geometry: IdeRenderGeometry,
    val model: IdeDrawModel,
    treeFirstRow: Int,
) {
    val pointer: IdePointerContext by lazy {
        when (val page = state.page) {
            IdePageState.Opening -> {
                IdePointerContext(geometry)
            }

            is IdePageState.Start -> {
                IdePointerContext(
                    geometry,
                    projects = page.projects,
                    hitTargets = model.hitTargets,
                    dialog = state.dialog,
                )
            }

            is IdePageState.Workspace -> {
                IdePointerContext(
                    geometry,
                    editor =
                        (page.value.editor as? IdeEditorView.Text).takeUnless {
                            page.value.git.visible &&
                                page.value.git.tab == ru.lazyhat.compukters.ide.client.state.IdeGitTab.Diff
                        },
                    gitVisible = page.value.git.visible,
                    gitMenu = page.value.git.menu,
                    gitScrollMaximum = model.gitScrollMaximum ?: 0,
                    gitDraft = page.value.git.draft,
                    gitPreviewScrollMaximum = model.gitPreviewScrollMaximum ?: 0,
                    gitPreviewBounds = model.gitPreviewBounds,
                    bottomTab = page.value.bottom.tab,
                    bottomScrollMaximum = model.bottomScrollMaximum,
                    gitFieldFocusScroll = model.gitFieldFocusScroll,
                    usages =
                        page.value.usages.takeIf {
                            page.value.bottom.tab ==
                                ru.lazyhat.compukters.ide.client.state.IdeBottomTab.Problems
                        },
                    projects = page.value.projects,
                    tree = if (page.value.git.visible) emptyList() else page.value.tree.flatten(),
                    explorer = if (page.value.git.visible) emptyList() else page.value.explorerRows(),
                    treeFirstRow = treeFirstRow,
                    hitTargets = model.hitTargets,
                    dialog = state.dialog,
                )
            }
        }
    }

    fun focus(area: IdeFocusArea): IdeFocusState {
        val editor = ((state.page as? IdePageState.Workspace)?.value?.editor as? IdeEditorView.Text)
        val analysis = editor?.analysis as? IdeAnalysisState.Active
        val completion = analysis?.completion != null
        val chooser =
            (analysis?.interaction as? IdeSemanticInteraction.Chooser) != null
        return IdeFocusState(
            area,
            completion,
            chooser,
            state.dialog,
            geometry.codeRows.coerceAtLeast(1),
            analysis?.parameterInfo != null,
            gitVisible = (state.page as? IdePageState.Workspace)?.value?.git?.visible == true,
            gitDiffVisible =
                (state.page as? IdePageState.Workspace)?.value?.git?.let {
                    it.visible &&
                        it.tab == ru.lazyhat.compukters.ide.client.state.IdeGitTab.Diff &&
                        it.previewPath != null
                } ==
                    true,
            gitPreviewScrollMaximum = pointer.gitPreviewScrollMaximum,
            gitScrollMaximum = pointer.gitScrollMaximum,
            gitFieldFocusScroll = pointer.gitFieldFocusScroll,
            gitDraft = (state.page as? IdePageState.Workspace)?.value?.git?.draft,
            gitMenu = (state.page as? IdePageState.Workspace)?.value?.git?.menu,
            importFixesVisible = analysis?.importFixes != null,
            findVisible = editor?.find != null,
            usagesFocused =
                (state.page as? IdePageState.Workspace)?.value?.let {
                    it.bottom.tab == ru.lazyhat.compukters.ide.client.state.IdeBottomTab.Problems && it.usages?.focused == true
                } == true,
            findFocused = editor?.find?.focused == true,
            findSelectedText = editor?.find?.let { find -> find.querySelection?.let { find.query.substring(it.startUtf16, it.endUtf16) } },
        )
    }
}
