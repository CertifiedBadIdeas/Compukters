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

package ru.lazyhat.compukters.ide.client.analysis

import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.DeclarationLocation
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.editor.EditorRange
import java.util.Collections

class IdeRenamePlan(
    val identity: AnalysisSnapshotIdentity,
    val activePath: VirtualSourcePath,
    val documentRevision: Long,
    val newName: String,
    sources: Map<VirtualSourcePath, String>,
    locations: List<DeclarationLocation.Source>,
    manifestBytes: ByteArray,
    lockBytes: ByteArray?,
) {
    private val manifest = manifestBytes.copyOf()
    private val lock = lockBytes?.copyOf()

    fun matchesConfiguration(input: ru.lazyhat.compukters.ide.client.workspace.IdeBuildInput): Boolean =
        manifest.contentEquals(input.manifestBytes) &&
            (lock?.contentEquals(input.lockBytes ?: return false) ?: (input.lockBytes == null))

    val sources: Map<VirtualSourcePath, String> = Collections.unmodifiableMap(sources.toMap())
    val edits: Map<VirtualSourcePath, List<EditorRange>> =
        Collections.unmodifiableMap(
            locations.distinct().groupBy { it.path }.mapValues { (_, values) ->
                Collections.unmodifiableList(values.map { it.range }.sortedBy { it.startUtf16 })
            },
        )

    init {
        require(locations.isNotEmpty() && locations.all { it.origin == DeclarationOrigin.Project })
        require(newName.isNotEmpty())
        edits.forEach { (path, ranges) ->
            val source = sources.getValue(path)
            require(ranges.all { it.startUtf16 < it.endUtf16 && it.endUtf16 <= source.length })
            require(ranges.zipWithNext().all { (first, second) -> first.endUtf16 <= second.startUtf16 })
        }
    }

    fun replacement(path: VirtualSourcePath): String =
        StringBuilder(sources.getValue(path))
            .apply {
                edits.getValue(path).asReversed().forEach { replace(it.startUtf16, it.endUtf16, newName) }
            }.toString()

    fun caret(
        path: VirtualSourcePath,
        offset: Int,
    ): Int {
        var delta = 0
        edits.getValue(path).forEach { range ->
            if (offset < range.startUtf16) return offset + delta
            if (offset < range.endUtf16) return range.startUtf16 + delta + minOf(offset - range.startUtf16, newName.length)
            delta += newName.length - range.length
        }
        return offset + delta
    }
}

sealed interface IdeRenameOutcome {
    data class Prepared(
        val plan: IdeRenamePlan,
    ) : IdeRenameOutcome

    data class Failed(
        val detail: String,
    ) : IdeRenameOutcome
}
