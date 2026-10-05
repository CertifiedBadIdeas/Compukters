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

import ru.lazyhat.compukters.addon.api.AddonGuestApiBundleCodec
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.ide.analysis.AnalysisModuleIdentity
import ru.lazyhat.compukters.ide.analysis.CompletionItem
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.compiler.profile.PlatformCatalog
import ru.lazyhat.compukters.ide.compiler.profile.TargetCompileProfile
import ru.lazyhat.compukters.ide.project.AddonId
import ru.lazyhat.compukters.ide.project.ProjectManifest
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import java.util.concurrent.atomic.AtomicReference

data class IdeCompletionEntry(
    val proposal: CompletionItem,
    val actionText: String?,
    val addonRequirement: IdeCompletionAddonRequirement?,
)

data class IdeCompletionAddonRequirement(
    val id: AddonId,
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

    // A target profile is immutable. Retain one decoded index, not one decode per proposal.
    private val addonIndex = AtomicReference<AddonIndex>()

    private class AddonIndex(
        val target: TargetCompileProfile?,
        val ids: Map<AnalysisModuleIdentity, AddonId?>,
    )

    private fun addonIds(target: TargetCompileProfile?): Map<AnalysisModuleIdentity, AddonId?> {
        addonIndex.get()?.takeIf { it.target === target }?.let { return it.ids }
        val ids = linkedMapOf<AnalysisModuleIdentity, AddonId?>()
        target?.addonBundles?.forEach { payload ->
            val bundle = AddonGuestApiBundleCodec.decode(payload.content.toByteArray())
            val identity = AnalysisModuleIdentity(bundle.moduleDescriptor.id.toString(), payload.identity.hash)
            // Preserve the previous singleOrNull behavior for ambiguous advertised origins.
            ids[identity] = if (identity in ids) null else AddonId(bundle.identity.id)
        }
        val index = AddonIndex(target, ids.toMap())
        addonIndex.set(index)
        return index.ids
    }

    fun plan(
        proposals: List<CompletionItem>,
        manifest: ProjectManifest,
        target: TargetCompileProfile?,
    ): List<IdeCompletionEntry> {
        val needsAddonOrigins =
            proposals.any { proposal ->
                val origin = proposal.origin
                origin is DeclarationOrigin.Platform && origin.identity != builtinsIdentity
            }
        val ids = if (needsAddonOrigins) addonIds(target) else emptyMap()
        return proposals.map { proposal ->
            val origin = proposal.origin
            val requirement =
                if (origin is DeclarationOrigin.Platform && origin.identity != builtinsIdentity) {
                    val addon = ids[origin.identity]
                    if (addon == null || addon in manifest.addons) null else IdeCompletionAddonRequirement(addon)
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
}
