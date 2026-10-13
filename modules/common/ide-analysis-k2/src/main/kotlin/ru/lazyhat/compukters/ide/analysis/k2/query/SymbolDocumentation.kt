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

package ru.lazyhat.compukters.ide.analysis.k2.query

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import ru.lazyhat.compukters.ide.analysis.DeclarationLocation
import ru.lazyhat.compukters.ide.analysis.k2.standalone.AdmittedK2Snapshot
import ru.lazyhat.compukters.ide.editor.EditorRange

internal object SymbolDocumentation {
    fun KaSession.documentation(
        symbol: KaSymbol,
        snapshot: AdmittedK2Snapshot,
        maximumBytes: Int,
    ): String? {
        val source = symbol.psi as? KtNamedDeclaration
        val declaration =
            if (source?.docComment != null) {
                source
            } else {
                val mapped = DeclarationOriginMapper.run { map(symbol, snapshot) }
                val (file, range) =
                    when (mapped) {
                        is MappedDeclaration.PlatformTarget -> {
                            (snapshot.platformSourceFiles[mapped.sourcePath] ?: return null) to
                                EditorRange(mapped.startUtf16, mapped.endUtf16)
                        }

                        is MappedDeclaration.Location -> {
                            val location = mapped.value as? DeclarationLocation.Source ?: return null
                            (snapshot.files[location.path] ?: return null) to location.range
                        }

                        null -> {
                            return null
                        }
                    }
                if (file.textLength == 0) return null
                generateSequence(file.findElementAt(range.startUtf16.coerceAtMost(file.textLength - 1))) { it.parent }
                    .filterIsInstance<KtNamedDeclaration>()
                    .firstOrNull { it.textRange.startOffset <= range.startUtf16 && it.textRange.endOffset >= range.endUtf16 }
            }
        val raw = declaration?.docComment?.text ?: return null
        val text =
            raw
                .removePrefix("/**")
                .removeSuffix("*/")
                .lines()
                .mapIndexed { index, line ->
                    val margin = line.trimStart()
                    when {
                        margin.startsWith('*') -> margin.removePrefix("*").removePrefix(" ")
                        index == 0 -> line.removePrefix(" ")
                        else -> line
                    }
                }.dropWhile(String::isBlank)
                .dropLastWhile(String::isBlank)
                .joinToString("\n")
                .trimEnd()
        return boundedUtf8(text, maximumBytes).takeIf { it.isNotBlank() }
    }
}
