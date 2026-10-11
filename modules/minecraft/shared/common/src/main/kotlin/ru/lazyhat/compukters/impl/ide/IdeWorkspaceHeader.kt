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

import ru.lazyhat.compukters.ide.client.state.IdeWorkspaceView
import ru.lazyhat.compukters.ide.client.target.IdeTargetState

/** Header layout reserves independent bounds for project navigation, file and target. */
internal object IdeWorkspaceHeader {
    fun draw(
        output: IdeDrawBuilder,
        geometry: IdeRenderGeometry,
        workspace: IdeWorkspaceView,
        target: IdeTargetState,
        projectSwitcherOpen: Boolean,
    ): IdeRect {
        val header = geometry.header
        val title = workspace.project.displayName
        val maximum = (header.width / 3).coerceAtLeast(64)
        val width = (output.uiWidth(title) + 24).coerceIn(64, maximum)
        val project = IdeRect(header.left + 4, header.top + 3, header.left + 4 + width, header.bottom - 3)
        output.target(
            IdeHitAction.ProjectSwitcher,
            project,
            true,
            "Choose or manage projects",
            selected = projectSwitcherOpen,
            style = IdeControlStyle.Neutral,
        )
        output.ui(IdeTextKind.Header, "${output.fitUi(title, project.width - 22)} ▾", project.left + 6, project.top + 5, clip = project)
        val right = header.right - IdeUiScaleControl.WIDTH - 8
        val targetText = targetLabel(target)
        val targetWidth = minOf(output.uiWidth(targetText), ((right - project.right - 16) / 2).coerceAtLeast(0))
        val targetLeft = right - targetWidth
        val file = IdeRect(project.right + 8, header.top, (targetLeft - 8).coerceAtLeast(project.right + 8), header.bottom)
        val active = workspace.activeFile?.value ?: "No file"
        val colors = IdeGitFileColors(workspace.git.status ?: workspace.git.result?.status)
        output.ui(IdeTextKind.Header, output.fitUi(active, file.width), file.left, header.top + 7, colors.color(active), file)
        val targetBounds = IdeRect(targetLeft, header.top, right, header.bottom)
        output.ui(
            IdeTextKind.Header,
            output.fitUi(targetText, targetBounds.width),
            targetBounds.left,
            header.top + 7,
            IdeColors.MUTED,
            targetBounds,
        )
        return project
    }
}
