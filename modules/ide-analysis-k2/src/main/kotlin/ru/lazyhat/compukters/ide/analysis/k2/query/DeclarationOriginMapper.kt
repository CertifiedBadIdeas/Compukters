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
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.containingModule
import org.jetbrains.kotlin.analysis.api.components.render
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.symbols.symbol
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import ru.lazyhat.compukters.ide.analysis.DeclarationLocation
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.analysis.k2.standalone.AdmittedK2Snapshot
import ru.lazyhat.compukters.ide.editor.EditorRange

internal object DeclarationOriginMapper {
    @OptIn(KaExperimentalApi::class)
    fun KaSession.map(
        symbol: KaSymbol,
        snapshot: AdmittedK2Snapshot,
    ): MappedDeclaration? {
        val declaration = symbol.psi as? KtNamedDeclaration
        val name = declaration?.nameIdentifier
        val path =
            snapshot.files.entries
                .firstOrNull { (_, file) -> file == declaration?.containingKtFile }
                ?.key
        if (path != null && name != null) {
            return MappedDeclaration.Location(
                DeclarationLocation.Source(
                    DeclarationOrigin.Project,
                    path,
                    EditorRange(name.textRange.startOffset, name.textRange.endOffset),
                ),
            )
        }
        val module = symbol.containingModule
        val binaryRoot =
            snapshot.environment.binaryModules.entries
                .firstOrNull { (_, candidate) -> candidate == module }
                ?.key
        val bundle = snapshot.bundles.singleOrNull { it.classRoot == binaryRoot } ?: return null
        val targetName = (symbol as? KaNamedSymbol)?.name?.asString()
        val targetSignature =
            (symbol as? KaDeclarationSymbol)?.render(KaDeclarationRendererForSource.WITH_QUALIFIED_NAMES)
        return if (targetName != null && targetSignature != null && snapshot.bundleSourceFiles[bundle.identity].orEmpty().isNotEmpty()) {
            MappedDeclaration.BundleTarget(bundle.identity, targetName, symbol.stableId(), targetSignature)
        } else {
            MappedDeclaration.Location(DeclarationLocation.SourceUnavailable(DeclarationOrigin.Bundle(bundle.identity)))
        }
    }

    @OptIn(KaExperimentalApi::class)
    fun resolveBundleSource(
        target: MappedDeclaration.BundleTarget,
        snapshot: AdmittedK2Snapshot,
    ): DeclarationLocation {
        snapshot.bundleSourceFiles[target.identity].orEmpty().entries.sortedBy { it.key.value }.forEach { (sourcePath, file) ->
            val match =
                analyze(file) {
                    var match: DeclarationLocation.Source? = null
                    file.accept(
                        object : KtTreeVisitorVoid() {
                            override fun visitNamedDeclaration(declaration: KtNamedDeclaration) {
                                if (match == null && declaration.name == target.name) {
                                    val candidateSignature = declaration.symbol.render(KaDeclarationRendererForSource.WITH_QUALIFIED_NAMES)
                                    val identifier = declaration.nameIdentifier
                                    if (declaration.symbol.stableId() == target.stableId && candidateSignature == target.signature &&
                                        identifier != null
                                    ) {
                                        match =
                                            DeclarationLocation.Source(
                                                DeclarationOrigin.Bundle(target.identity),
                                                sourcePath,
                                                EditorRange(identifier.textRange.startOffset, identifier.textRange.endOffset),
                                            )
                                    }
                                }
                                if (match == null) super.visitNamedDeclaration(declaration)
                            }
                        },
                    )
                    match
                }
            match?.let { return it }
        }
        return DeclarationLocation.SourceUnavailable(DeclarationOrigin.Bundle(target.identity))
    }
}

private fun KaSymbol.stableId(): String? =
    when (this) {
        is KaCallableSymbol -> callableId?.toString()
        is KaClassLikeSymbol -> classId?.asString()
        else -> null
    }

internal sealed interface MappedDeclaration {
    data class Location(
        val value: DeclarationLocation,
    ) : MappedDeclaration

    data class BundleTarget(
        val identity: ru.lazyhat.compukters.ide.analysis.AnalysisBundleIdentity,
        val name: String,
        val stableId: String?,
        val signature: String,
    ) : MappedDeclaration
}
