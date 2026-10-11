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
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.tree.ProjectFileSearch
import ru.lazyhat.compukters.ide.project.tree.ProjectTree

/** Modal file navigation owns only query/selection, not another project filesystem model. */
class IdeQuickOpen : AutoCloseable {
    private var editor: IdeTextField? = null
    private var project: String? = null
    private var tree: ProjectTree? = null
    private var preferred: List<ProjectPath> = emptyList()
    private var results: List<ProjectPath> = emptyList()
    private var selected = 0
    private var first = 0
    val visible: Boolean get() = editor != null

    fun open(workspace: IdeWorkspaceView) {
        close()
        project = workspace.project.directoryName
        tree = workspace.tree
        preferred = listOfNotNull(workspace.activeFile) + workspace.openFiles.asReversed().map { it.path }
        editor = IdeTextField("", 256)
        search()
    }

    fun synchronize(workspace: IdeWorkspaceView?) {
        if (!visible) return
        if (workspace == null || workspace.project.directoryName != project) {
            close()
            return
        }
        if (workspace.tree !== tree) {
            val path = results.getOrNull(selected)
            tree = workspace.tree
            search()
            selected = results.indexOf(path).coerceAtLeast(0)
        }
    }

    private fun search() {
        results = tree?.let { ProjectFileSearch.search(it.flatten(), editor?.view()?.text ?: "", preferred) } ?: emptyList()
        selected = 0
        first = 0
    }

    fun view(panel: IdeRect): IdeQuickOpenView? {
        val field = editor ?: return null
        val geometry = IdeQuickOpenGeometry.compute(panel)
        field.layout(geometry.field)
        if (selected < first) first = selected
        if (selected >= first + geometry.rows) first = selected - geometry.rows + 1
        first = first.coerceIn(0, (results.size - geometry.rows).coerceAtLeast(0))
        return IdeQuickOpenView(field.view(), results, selected, first)
    }

    fun type(text: String) {
        val field = editor ?: return
        val before = field.view().text
        field.type(text)
        if (before != field.view().text) search()
    }

    fun key(
        event: IdeKeyInput,
        clipboard: () -> String,
        copy: (String) -> Unit,
        panel: IdeRect,
    ) {
        when (event.key) {
            IdeKeyCode.UP -> {
                selected = (selected - 1).coerceAtLeast(0)
            }

            IdeKeyCode.DOWN -> {
                selected = (selected + 1).coerceAtMost((results.size - 1).coerceAtLeast(0))
            }

            IdeKeyCode.PAGE_UP -> {
                selected = (selected - IdeQuickOpenGeometry.compute(panel).rows).coerceAtLeast(0)
            }

            IdeKeyCode.PAGE_DOWN -> {
                selected =
                    (selected + IdeQuickOpenGeometry.compute(panel).rows).coerceAtMost((results.size - 1).coerceAtLeast(0))
            }

            else -> {
                val field = editor ?: return
                val before = field.view().text
                field.keyPressed(event, clipboard, copy)
                if (before != field.view().text) search()
            }
        }
    }

    fun press(
        x: Double,
        y: Double,
        panel: IdeRect,
        shift: Boolean,
        doubleClick: Boolean,
    ): ProjectPath? {
        val geometry = IdeQuickOpenGeometry.compute(panel)
        val field = editor ?: return null
        if (field.press(x, y, geometry.field, shift, doubleClick)) return null
        if (geometry.list.containsPoint(x, y)) {
            val row = first + ((y - geometry.list.top) / IdeQuickOpenGeometry.ROW_HEIGHT).toInt()
            if (row in results.indices) {
                selected = row
                if (doubleClick) return takeSelected()
            }
        }
        return null
    }

    fun drag(
        x: Double,
        panel: IdeRect,
    ) {
        editor?.drag(x, IdeQuickOpenGeometry.compute(panel).field)
    }

    fun release() {
        editor?.release()
    }

    fun scroll(
        x: Double,
        y: Double,
        direction: Int,
        panel: IdeRect,
    ) {
        val geometry = IdeQuickOpenGeometry.compute(panel)
        if (geometry.field.containsPoint(x, y)) editor?.scroll(direction * 3)
        if (geometry.list.containsPoint(x, y)) selected = (selected + direction * 3).coerceIn(0, (results.size - 1).coerceAtLeast(0))
    }

    fun takeSelected(): ProjectPath? = results.getOrNull(selected)?.also { close() }

    override fun close() {
        editor?.close()
        editor = null
        tree = null
        project = null
        results = emptyList()
        preferred = emptyList()
        selected = 0
        first = 0
    }
}

data class IdeQuickOpenView(
    val field: IdeTextFieldView,
    val results: List<ProjectPath>,
    val selected: Int,
    val first: Int,
)

data class IdeQuickOpenGeometry(
    val bounds: IdeRect,
    val field: IdeRect,
    val list: IdeRect,
    val rows: Int,
) {
    companion object {
        const val ROW_HEIGHT = 30

        fun compute(panel: IdeRect): IdeQuickOpenGeometry {
            val width = minOf(480, panel.width - 32)
            val height = minOf(316, panel.height - 40)
            val left = panel.left + (panel.width - width) / 2
            val top = panel.top + (panel.height - height) / 3
            val bounds = IdeRect(left, top, left + width, top + height)
            val field = IdeRect(left + 12, top + 30, bounds.right - 12, top + 54)
            val rows = ((height - 104) / ROW_HEIGHT).coerceAtLeast(1)
            val list = IdeRect(left + 12, top + 78, bounds.right - 12, top + 78 + rows * ROW_HEIGHT)
            return IdeQuickOpenGeometry(bounds, field, list, rows)
        }
    }
}
