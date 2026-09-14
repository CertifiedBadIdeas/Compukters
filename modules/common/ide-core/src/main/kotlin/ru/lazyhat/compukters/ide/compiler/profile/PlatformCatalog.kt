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

package ru.lazyhat.compukters.ide.compiler.profile

import ru.lazyhat.compukters.addon.api.AddonGuestApiBundleCodec
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundlePayload
import ru.lazyhat.compukters.ide.project.AddonId
import ru.lazyhat.compukters.ide.project.ApiMajor
import ru.lazyhat.compukters.ide.project.ModuleId
import ru.lazyhat.compukters.ide.project.ResolvedAddon
import ru.lazyhat.compukters.ide.project.ResolvedModule
import ru.lazyhat.compukters.platform.bundle.PlatformBundle
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import ru.lazyhat.compukters.platform.bundle.PlatformModule
import ru.lazyhat.compukters.platform.bundle.PlatformModuleGraph
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import java.util.Collections

data class PlatformCatalogEntry(
    val identity: ResolvedModule,
    val descriptor: PlatformModule,
)

data class ResolvedPlatformModule(
    val identity: ResolvedModule,
    val descriptor: PlatformModule,
    val direct: Boolean,
)

data class PlatformAddonEntry(
    val identity: ResolvedAddon,
    val descriptor: PlatformModule,
    val payload: TrustedBundlePayload,
)

class ResolvedPlatformSelection internal constructor(
    modules: List<ResolvedPlatformModule>,
    addons: List<ResolvedAddon>,
) {
    val modules: List<ResolvedPlatformModule> = Collections.unmodifiableList(modules.toList())
    val addons: List<ResolvedAddon> = Collections.unmodifiableList(addons.sortedBy { it.id })
}

class PlatformCatalog private constructor(
    val bundle: PlatformBundle,
    val baseBundle: PlatformBundle,
    entries: List<PlatformCatalogEntry>,
    addonEntries: List<PlatformAddonEntry>,
) {
    val entries: List<PlatformCatalogEntry> =
        Collections.unmodifiableList(
            entries.sortedWith(
                compareBy(MODULE_ID_COMPARATOR) { it.identity.id },
            ),
        )
    private val byId = this.entries.associateBy { it.identity.id }
    private val graph = PlatformModuleGraph(bundle)
    val addons: List<PlatformAddonEntry> = Collections.unmodifiableList(addonEntries.sortedBy { it.identity.id })
    private val addonsById = this.addons.associateBy { it.identity.id }

    fun find(id: ModuleId): PlatformCatalogEntry? = byId[id]

    fun require(id: ModuleId): PlatformCatalogEntry = requireNotNull(find(id)) { "platform module ${id.value} is unavailable" }

    fun findAddon(id: AddonId): PlatformAddonEntry? = addonsById[id]

    fun addonBundlesFor(addons: Set<AddonId>): List<TrustedBundlePayload> =
        addons
            .map { id -> requireNotNull(addonsById[id]) { "addon ${id.value} is unavailable" }.payload }
            .sortedBy { it.identity.name }

    fun resolve(requirements: Set<AddonId>): ResolvedPlatformSelection {
        val selectedAddons = requirements.map { id -> requireNotNull(findAddon(id)) { "addon ${id.value} is unavailable" } }
        val directModuleIds = selectedAddons.mapTo(mutableSetOf()) { projectId(it.descriptor.id) }
        val roots = bundle.modules.mapTo(mutableSetOf()) { it.id }
        roots += selectedAddons.map { it.descriptor.id }
        val resolved = graph.resolve(roots)
        return ResolvedPlatformSelection(
            resolved.modules.map { descriptor ->
                val id = projectId(descriptor.id)
                val entry = require(id)
                ResolvedPlatformModule(entry.identity, entry.descriptor, id in directModuleIds)
            },
            selectedAddons.map(PlatformAddonEntry::identity),
        )
    }

    companion object {
        fun of(bundle: PlatformBundle): PlatformCatalog = PlatformCatalog(bundle, bundle, bundle.modules.map(::entry), emptyList())

        fun forTarget(
            bundle: PlatformBundle,
            advertised: List<ResolvedModule>,
            addonBundles: List<TrustedBundlePayload> = emptyList(),
        ): PlatformCatalog {
            val local = of(bundle)
            val decodedAddons =
                addonBundles.map { payload ->
                    val decoded = AddonGuestApiBundleCodec.decode(payload.content.toByteArray())
                    require(
                        decoded.identity.id == payload.identity.name,
                    ) { "target addon bundle identity does not match payload" }
                    require(
                        decoded.identity.platformAbi == bundle.identity.platformAbi,
                    ) { "target addon bundle platform ABI does not match bundle" }
                    require(
                        decoded.identity.contentHash
                            .toByteArray()
                            .contentEquals(payload.identity.hash.toByteArray()),
                    ) {
                        "target addon bundle content hash does not match payload"
                    }
                    val id = projectId(decoded.moduleDescriptor.id)
                    PlatformAddonEntry(
                        identity =
                            ResolvedAddon(
                                AddonId(decoded.identity.id),
                                decoded.identity.version,
                                payload.identity.hash,
                            ),
                        descriptor = decoded.moduleDescriptor,
                        payload = payload,
                    )
                }
            require(decodedAddons.distinctBy { it.identity.id }.size == decodedAddons.size) { "target addon IDs must be unique" }
            val externalEntries = decodedAddons.associateBy { projectId(it.descriptor.id) }
            require(externalEntries.size == addonBundles.size) { "target addon bundle module IDs must be unique" }
            require(externalEntries.keys.none(local.byId::containsKey)) { "target addon bundles cannot shadow packaged platform modules" }
            val advertisedById = advertised.associateBy(ResolvedModule::id)
            require(advertisedById.size == advertised.size) { "target platform module IDs must be unique" }
            val entries =
                advertised.map { actual ->
                    val expected =
                        externalEntries[actual.id]?.let { entry(it.descriptor, it.identity.contentHash) } ?: local.require(actual.id)
                    require(expected.identity == actual) { "target platform module ${actual.id.value} identity does not match bundle" }
                    expected
                }
            val availableIds = advertisedById.keys
            entries.forEach { entry ->
                entry.descriptor.dependencies.forEach { dependency ->
                    if (dependency != bundle.builtins.id) {
                        val dependencyId = projectId(dependency)
                        require(dependencyId in availableIds) {
                            "target platform module ${entry.identity.id.value} has unavailable dependency ${dependencyId.value}"
                        }
                    }
                }
            }
            val merged =
                PlatformBundle(
                    bundle.identity,
                    bundle.builtins,
                    bundle.modules + externalEntries.values.map(PlatformAddonEntry::descriptor),
                )
            return PlatformCatalog(merged, bundle, entries, decodedAddons)
        }

        private fun entry(
            module: PlatformModule,
            contentHash: Hash256 = Hash256.of(PlatformBundleCodec.moduleContentHash(module).toByteArray()),
        ): PlatformCatalogEntry {
            val majorText = module.version.substringBefore('.')
            val major = majorText.toIntOrNull()
            require(major != null && major in 0..ApiMajor.MAXIMUM) {
                "platform module ${module.id} version must begin with a supported API major"
            }
            return PlatformCatalogEntry(
                identity =
                    ResolvedModule(
                        id = projectId(module.id),
                        major = ApiMajor(major),
                        version = module.version,
                        contentHash = contentHash,
                    ),
                descriptor = module,
            )
        }
    }
}

private val MODULE_ID_COMPARATOR = Comparator<ModuleId> { left, right -> compareUtf8(left.value, right.value) }

private fun compareUtf8(
    left: String,
    right: String,
): Int {
    val leftBytes = left.encodeToByteArray()
    val rightBytes = right.encodeToByteArray()
    repeat(minOf(leftBytes.size, rightBytes.size)) { index ->
        val result = (leftBytes[index].toInt() and 0xff).compareTo(rightBytes[index].toInt() and 0xff)
        if (result != 0) return result
    }
    return leftBytes.size.compareTo(rightBytes.size)
}

private fun platformId(id: ModuleId): PlatformModuleId = PlatformModuleId(id.provider, id.module)

private fun projectId(id: PlatformModuleId): ModuleId = ModuleId(id.namespace, id.name)
