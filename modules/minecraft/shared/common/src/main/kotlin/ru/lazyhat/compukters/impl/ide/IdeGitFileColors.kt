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

import ru.lazyhat.compukters.ide.git.GitChange
import ru.lazyhat.compukters.ide.git.GitStatus

/** One projection shared by the project tree, active title and Commit rows. */
class IdeGitFileColors(
    status: GitStatus?,
) {
    private val colors = mutableMapOf<String, Kind>()

    init {
        if (status?.available == true) {
            status.changes.forEach { change ->
                var path = change.path.value
                val kind = kind(change)
                while (path.isNotEmpty()) {
                    if ((colors[path]?.priority ?: -1) < kind.priority) colors[path] = kind
                    path = path.substringBeforeLast('/', "")
                }
            }
        }
    }

    fun color(path: String): Int = colors[path]?.color ?: IdeColors.TEXT

    private enum class Kind(
        val priority: Int,
        val color: Int,
    ) {
        Untracked(0, IdeColors.GIT_UNTRACKED),
        Added(1, IdeColors.GIT_ADDED),
        Deleted(2, IdeColors.GIT_DELETED),
        Modified(3, IdeColors.GIT_MODIFIED),
        Conflict(4, IdeColors.GIT_CONFLICT),
    }

    companion object {
        fun color(change: GitChange): Int = kind(change).color

        private fun kind(change: GitChange): Kind =
            when {
                change.index == "conflict" || change.workingTree == "conflict" -> Kind.Conflict
                change.index == "added" -> Kind.Added
                change.index == "deleted" || change.workingTree == "deleted" -> Kind.Deleted
                change.workingTree == "untracked" -> Kind.Untracked
                else -> Kind.Modified
            }
    }
}
