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

package ru.lazyhat.compukters.ide.project.tree

import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import java.util.Locale

/** Search the admitted project snapshot; never read the filesystem on the input thread. */
object ProjectFileSearch {
    fun search(
        entries: List<ProjectTreeEntry>,
        query: String,
        preferred: List<ProjectPath> = emptyList(),
        limit: Int = 100,
    ): List<ProjectPath> {
        require(limit > 0)
        val normalized = query.trim().replace('\\', '/').lowercase(Locale.ROOT)
        val terms = normalized.split(Regex("\\s+")).filter(String::isNotEmpty)
        val priority = preferred.distinct().withIndex().associate { it.value to it.index }
        return entries
            .asSequence()
            .filter { it.kind != ProjectFileKind.Directory }
            .mapNotNull { entry ->
                val path = entry.path.value.lowercase(Locale.ROOT)
                val name = path.substringAfterLast('/')
                if (terms.any { it !in path }) return@mapNotNull null
                val rank =
                    when {
                        terms.isEmpty() -> 0
                        name == normalized -> 0
                        name.startsWith(normalized) -> 1
                        terms.all { it in name } -> 2
                        else -> 3
                    }
                Triple(entry.path, rank, if (terms.isEmpty()) priority[entry.path] ?: Int.MAX_VALUE else 0)
            }.sortedWith(compareBy<Triple<ProjectPath, Int, Int>> { it.second }.thenBy { it.third }.thenBy { it.first.value })
            .take(limit)
            .map { it.first }
            .toList()
    }
}
