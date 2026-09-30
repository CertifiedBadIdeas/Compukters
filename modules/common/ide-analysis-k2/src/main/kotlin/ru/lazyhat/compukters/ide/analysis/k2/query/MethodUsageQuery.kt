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

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.resolveSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.symbol
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.MethodUsageCount
import ru.lazyhat.compukters.ide.analysis.k2.standalone.AdmittedK2Snapshot
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.editor.EditorRange

/** Called within the presentation's K2 session: one project pass, not one query per declaration. */
internal object MethodUsageQuery {
    @OptIn(KaExperimentalApi::class)
    fun collect(
        session: KaSession,
        path: VirtualSourcePath,
        file: KtFile,
        snapshot: AdmittedK2Snapshot,
        limits: AnalysisLimits,
    ): List<MethodUsageCount> {
        val targets = linkedMapOf<KaSymbol, MethodUsageCount>()
        file.accept(
            object : KtTreeVisitorVoid() {
                override fun visitNamedFunction(function: KtNamedFunction) {
                    function.nameIdentifier?.let { name ->
                        if (targets.size >=
                            limits.semanticTokens
                        ) {
                            throw AnalysisOutputLimitException("method usage counts exceed negotiated limit")
                        }
                        targets[with(session) { function.symbol }] =
                            MethodUsageCount(path, EditorRange(name.textRange.startOffset, name.textRange.endOffset), 0)
                    }
                    super.visitNamedFunction(function)
                }
            },
        )
        if (targets.isEmpty()) return emptyList()
        snapshot.files.values.forEach { source ->
            source.accept(
                object : KtTreeVisitorVoid() {
                    override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                        if (expression is KtNameReferenceExpression) {
                            with(session) { expression.resolveSymbol() }?.let { symbol ->
                                targets[symbol]?.let { usage -> targets[symbol] = usage.copy(count = Math.incrementExact(usage.count)) }
                            }
                        }
                        super.visitSimpleNameExpression(expression)
                    }
                },
            )
        }
        return targets.values.sortedBy { it.range.startUtf16 }
    }
}
