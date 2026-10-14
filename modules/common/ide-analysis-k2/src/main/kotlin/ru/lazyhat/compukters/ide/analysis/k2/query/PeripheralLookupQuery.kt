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
import org.jetbrains.kotlin.analysis.api.base.KaConstantValue
import org.jetbrains.kotlin.analysis.api.components.evaluate
import org.jetbrains.kotlin.analysis.api.components.fakeOverrideOriginal
import org.jetbrains.kotlin.analysis.api.components.resolveCall
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.PeripheralLookup
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.editor.EditorRange

/** Only resolved platform discovery methods contribute world-independent lookup facts. */
internal object PeripheralLookupQuery {
    @OptIn(KaExperimentalApi::class)
    fun collect(
        session: KaSession,
        path: VirtualSourcePath,
        file: KtFile,
        limits: AnalysisLimits,
        snapshot: ru.lazyhat.compukters.ide.analysis.k2.standalone.AdmittedK2Snapshot,
    ): List<PeripheralLookup> {
        val result = mutableListOf<PeripheralLookup>()
        file.accept(
            object : KtTreeVisitorVoid() {
                override fun visitCallExpression(expression: KtCallExpression) {
                    super.visitCallExpression(expression)
                    with(session) {
                        val call = expression.resolveCall() as? KaFunctionCall<*> ?: return
                        val signature = call.partiallyAppliedSymbol.signature
                        val id = signature.symbol.fakeOverrideOriginal.callableId ?: return
                        if (id.classId?.asSingleFqName()?.asString() !in
                            setOf(
                                "compukter.peripheral.PeripheralProvider",
                                "compukter.peripheral.TypedPeripheralProvider",
                            )
                        ) {
                            return
                        }
                        val method = id.callableName.asString()
                        if (method != "named" && method != "namedOrNull") return
                        val argument =
                            call.valueArgumentMapping.entries
                                .singleOrNull { it.value.name.asString() == "name" }
                                ?.key ?: return
                        val name = (argument.evaluate() as? KaConstantValue.StringValue)?.value ?: return
                        val returnType = signature.returnType as? KaClassType ?: return
                        // Project-defined wrappers need contract-ID analysis; their class name is not host metadata.
                        if (returnType.symbol.psi?.containingFile in snapshot.files.values) return
                        val type = returnType.classId
                        if (type.isLocal || name.toByteArray(Charsets.UTF_8).size > 256) return
                        if (result.size >=
                            limits.declarationLocations
                        ) {
                            throw AnalysisOutputLimitException("peripheral lookups exceed negotiated limit")
                        }
                        val fact =
                            runCatching {
                                PeripheralLookup(
                                    path,
                                    EditorRange(argument.textRange.startOffset, argument.textRange.endOffset),
                                    type.asSingleFqName().asString(),
                                    name,
                                    method == "namedOrNull",
                                )
                            }.getOrNull() ?: return
                        result += fact
                    }
                }
            },
        )
        return result.sortedBy { it.range.startUtf16 }
    }
}
