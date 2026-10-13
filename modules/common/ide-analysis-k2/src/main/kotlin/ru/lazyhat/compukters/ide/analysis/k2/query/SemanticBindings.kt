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

import com.intellij.openapi.progress.ProgressManager
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.render
import org.jetbrains.kotlin.analysis.api.components.resolveSymbol
import org.jetbrains.kotlin.analysis.api.components.resolveToSymbols
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtExperimentalApi
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.DeclarationLocation
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.analysis.k2.standalone.AdmittedK2Snapshot
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.editor.EditorRange

/** Bounded binding snapshots shared by speculative semantic edits. */
internal object SemanticBindings {
    @OptIn(KaExperimentalApi::class, KtExperimentalApi::class)
    fun collect(
        snapshot: AdmittedK2Snapshot,
        limits: AnalysisLimits,
    ): List<Binding> {
        val result = ArrayList<Binding>()
        var signatureBytes = 0L
        snapshot.files.entries.sortedBy { it.key.value }.forEach { (path, file) ->
            analyze(file) {
                file.accept(
                    object : KtTreeVisitorVoid() {
                        override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                            ProgressManager.checkCanceled()
                            // Infix calls use operation references, unlike ordinary calls and named arguments.
                            // Symbolic operators remain excluded from editable sites.
                            if (expression is KtNameReferenceExpression ||
                                (
                                    expression is KtOperationReferenceExpression &&
                                        expression.getReferencedNameElementType() == KtTokens.IDENTIFIER
                                )
                            ) {
                                if (result.size >=
                                    limits.semanticTokens
                                ) {
                                    throw AnalysisOutputLimitException("rename binding count exceeds negotiated limit")
                                }
                                val symbol = expression.resolveSymbol() ?: expression.mainReference.resolveToSymbols().singleOrNull()
                                val target = symbol?.let { bindingKey(it, snapshot) }
                                if (target is SignatureBinding) {
                                    signatureBytes += target.value.encodeToByteArray().size
                                    if (signatureBytes >
                                        limits.sourceBytes.toLong() * 4
                                    ) {
                                        throw AnalysisOutputLimitException("rename binding text exceeds negotiated limit")
                                    }
                                }
                                result +=
                                    Binding(
                                        DeclarationLocation.Source(
                                            DeclarationOrigin.Project,
                                            path,
                                            EditorRange(expression.textRange.startOffset, expression.textRange.endOffset),
                                        ),
                                        expression.getReferencedName(),
                                        target,
                                    )
                            }
                            super.visitSimpleNameExpression(expression)
                        }
                    },
                )
            }
        }
        return result
    }

    @OptIn(KaExperimentalApi::class)
    private fun KaSession.bindingKey(
        symbol: KaSymbol,
        snapshot: AdmittedK2Snapshot,
    ): BindingKey? {
        val mapped = DeclarationOriginMapper.run { map(symbol, snapshot) }
        if (mapped is MappedDeclaration.Location && mapped.value is DeclarationLocation.Source &&
            mapped.value.origin == DeclarationOrigin.Project
        ) {
            return ProjectBinding(mapped.value)
        }
        if (mapped is MappedDeclaration.PlatformTarget) return PlatformBinding(mapped)
        return (symbol as? KaDeclarationSymbol)?.let { SignatureBinding(it.render(KaDeclarationRendererForSource.WITH_QUALIFIED_NAMES)) }
    }
}

internal data class Binding(
    val site: DeclarationLocation.Source,
    val name: String,
    val target: BindingKey?,
)

internal sealed interface BindingKey

internal data class ProjectBinding(
    val location: DeclarationLocation.Source,
) : BindingKey

internal data class PlatformBinding(
    val target: MappedDeclaration.PlatformTarget,
) : BindingKey

internal data class SignatureBinding(
    val value: String,
) : BindingKey

internal fun DeclarationLocation.Source.shift(shifts: Map<VirtualSourcePath, SourceOffsetShift>): DeclarationLocation.Source =
    shifts[path]?.let { copy(range = EditorRange(it.offset(range.startUtf16), it.offset(range.endUtf16, false))) } ?: this

internal class SourceOffsetShift(
    private val ranges: List<EditorRange>,
    newLength: Int,
) {
    private val deltas =
        IntArray(ranges.size + 1).apply {
            ranges.forEachIndexed { index, range -> this[index + 1] = this[index] + newLength - range.length }
        }

    fun offset(
        value: Int,
        includeInsertionAtBoundary: Boolean = true,
    ): Int {
        var low = 0
        var high = ranges.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (ranges[middle].endUtf16 <= value &&
                (includeInsertionAtBoundary || ranges[middle].length > 0 || ranges[middle].endUtf16 < value)
            ) {
                low = middle + 1
            } else {
                high =
                    middle
            }
        }
        return value + deltas[low]
    }
}
