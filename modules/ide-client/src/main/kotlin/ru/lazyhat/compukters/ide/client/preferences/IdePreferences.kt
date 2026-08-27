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

package ru.lazyhat.compukters.ide.client.preferences

import ru.lazyhat.compukters.ide.project.fs.ProjectPath

class IdePreferences private constructor(
    val lastProjectDirectory: String?,
    val lastFile: ProjectPath?,
    val caretUtf16: Int,
    val firstVisibleLine: Int,
    val firstVisibleColumn: Int,
    val treeWidth: Int,
    val diagnosticsHeight: Int,
    val diagnosticsExpanded: Boolean,
) {
    companion object {
        const val MIN_PANEL_SIZE = 32
        const val MAX_PANEL_SIZE = 16 * 1024

        fun admit(
            projectDirectory: String?,
            file: String?,
            caretUtf16: Int,
            firstVisibleLine: Int,
            firstVisibleColumn: Int,
            treeWidth: Int,
            diagnosticsHeight: Int,
            diagnosticsExpanded: Boolean,
        ): IdePreferences {
            val project = projectDirectory?.takeIf(::isDirectCanonicalName)
            val path =
                if (project == null) {
                    null
                } else {
                    file?.let { runCatching { ProjectPath.file(it) }.getOrNull() }
                }
            return IdePreferences(
                lastProjectDirectory = project,
                lastFile = path,
                caretUtf16 = caretUtf16.coerceAtLeast(0),
                firstVisibleLine = firstVisibleLine.coerceAtLeast(0),
                firstVisibleColumn = firstVisibleColumn.coerceAtLeast(0),
                treeWidth = treeWidth.coerceIn(MIN_PANEL_SIZE, MAX_PANEL_SIZE),
                diagnosticsHeight = diagnosticsHeight.coerceIn(MIN_PANEL_SIZE, MAX_PANEL_SIZE),
                diagnosticsExpanded = diagnosticsExpanded,
            )
        }

        private fun isDirectCanonicalName(value: String): Boolean = '/' !in value && runCatching { ProjectPath.file(value) }.isSuccess
    }
}

interface IdePreferencesStore {
    fun load(): IdePreferences?

    fun save(preferences: IdePreferences)
}
