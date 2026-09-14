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

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.ide.analysis.AnalysisModuleIdentity
import ru.lazyhat.compukters.ide.analysis.CompletionItem
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.compiler.profile.PlatformCatalog
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfile
import ru.lazyhat.compukters.ide.project.ModuleId
import ru.lazyhat.compukters.ide.project.ProjectManifest
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec

data class IdeCompletionEntry(
    val proposal: CompletionItem,
    val actionText: String?,
    val moduleRequirement: IdeCompletionModuleRequirement?,
)

data class IdeCompletionModuleRequirement(
    val id: ModuleId,
)

class IdeCompletionPlanner(
    private val catalog: PlatformCatalog,
) {
    private val builtinsIdentity =
        AnalysisModuleIdentity(
            catalog.bundle.builtins.id
                .toString(),
            Hash256.of(
                PlatformBundleCodec.moduleContentHash(catalog.bundle.builtins).toByteArray(),
            ),
        )

    fun plan(
        proposals: List<CompletionItem>,
        manifest: ProjectManifest,
        target: TargetCompileProfile?,
    ): List<IdeCompletionEntry> =
        proposals.mapNotNull { proposal ->
            val origin = proposal.origin
            val requirement =
                if (origin is DeclarationOrigin.Platform && origin.identity != builtinsIdentity) {
                    val identity =
                        if (target == null) {
                            catalog.entries
                                .singleOrNull { candidate ->
                                    candidate.identity.id.value == origin.identity.name &&
                                        candidate.identity.contentHash == origin.identity.hash
                                }?.identity
                        } else {
                            target.modules.singleOrNull { candidate ->
                                candidate.id.value == origin.identity.name && candidate.contentHash == origin.identity.hash
                            }
                        } ?: return@mapNotNull null
                    if (identity.id in manifest.modules) null else IdeCompletionModuleRequirement(identity.id)
                } else {
                    null
                }
            val actions =
                buildList {
                    proposal.symbol?.importFqName?.let { add("import $it") }
                    requirement?.let { add("enable ${it.id.value}") }
                }
            IdeCompletionEntry(proposal, actions.takeIf(List<String>::isNotEmpty)?.joinToString(" · "), requirement)
        }
}
