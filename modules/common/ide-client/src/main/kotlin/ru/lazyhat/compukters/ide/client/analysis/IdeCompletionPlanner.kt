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

import ru.lazyhat.compukters.ide.analysis.CompletionItem
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.compiler.profile.PlatformCatalog
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfile
import ru.lazyhat.compukters.ide.project.ApiMajor
import ru.lazyhat.compukters.ide.project.ModuleId
import ru.lazyhat.compukters.ide.project.ProjectManifest

data class IdeCompletionEntry(
    val proposal: CompletionItem,
    val actionText: String?,
    val moduleRequirement: IdeCompletionModuleRequirement?,
)

data class IdeCompletionModuleRequirement(
    val id: ModuleId,
    val major: ApiMajor,
)

class IdeCompletionPlanner(
    private val catalog: PlatformCatalog,
) {
    fun plan(
        proposals: List<CompletionItem>,
        manifest: ProjectManifest,
        target: TargetCompileProfile?,
    ): List<IdeCompletionEntry> =
        proposals.mapNotNull { proposal ->
            val origin = proposal.origin
            val requirement =
                if (origin is DeclarationOrigin.Platform) {
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
                    when (manifest.modules[identity.id]) {
                        null -> IdeCompletionModuleRequirement(identity.id, identity.major)
                        identity.major -> null
                        else -> return@mapNotNull null
                    }
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
