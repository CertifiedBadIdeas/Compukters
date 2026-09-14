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

import ru.lazyhat.compukters.addon.api.AddonGuestApiLimits
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundlePayload
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.project.AddonId
import ru.lazyhat.compukters.ide.project.ApiMajor
import ru.lazyhat.compukters.ide.project.ModuleId
import ru.lazyhat.compukters.ide.project.ResolvedAddon
import ru.lazyhat.compukters.ide.project.ResolvedModule
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import ru.lazyhat.compukters.platform.bundle.PlatformIdentity
import java.util.Collections

class CompileProfile(
    val toolchain: ToolchainLockIdentity,
    val platform: PlatformIdentity,
    addons: List<ResolvedAddon>,
    modules: List<ResolvedPlatformModule>,
    val limits: WorkerLimits,
    addonBundles: List<TrustedBundlePayload> = emptyList(),
) {
    val addons: List<ResolvedAddon> = Collections.unmodifiableList(addons.toList())
    val modules: List<ResolvedPlatformModule> = Collections.unmodifiableList(modules.toList())
    val addonBundles: List<TrustedBundlePayload> = Collections.unmodifiableList(addonBundles.toList())

    init {
        require(toolchain.languageVersion == platform.languageVersion) { "compile profile language version does not match platform" }
        require(toolchain.platformAbi.toByteArray().contentEquals(platform.contentHash.toByteArray())) {
            "compile profile platform ABI does not match platform"
        }
        val ids = this.modules.map { it.identity.id }
        require(ids.size == ids.toSet().size) { "compile profile module IDs must be unique" }
        require(
            this.addons
                .map(ResolvedAddon::id)
                .toSet()
                .size == this.addons.size,
        ) { "compile profile addon IDs must be unique" }
        require(
            this.addonBundles
                .map { it.identity.name }
                .toSet()
                .size == this.addonBundles.size,
        ) {
            "compile profile addon bundle names must be unique"
        }
        val selectedHashes = this.addons.mapTo(mutableSetOf()) { it.contentHash }
        require(this.addonBundles.all { bundle -> bundle.identity.hash in selectedHashes } && this.addonBundles.size == this.addons.size) {
            "compile profile addon bundles must exactly match selected addons"
        }
    }
}

class TargetCompileProfile(
    val toolchain: ToolchainLockIdentity,
    modules: List<ResolvedModule>,
    val limits: WorkerLimits,
    addonBundles: List<TrustedBundlePayload> = emptyList(),
) {
    val modules: List<ResolvedModule> =
        Collections.unmodifiableList(
            modules.sortedWith {
                left,
                right,
                ->
                compareValuesBy(left.id, right.id, ModuleId::provider, ModuleId::module)
            },
        )
    val addonBundles: List<TrustedBundlePayload> =
        Collections.unmodifiableList(addonBundles.sortedBy { it.identity.name })

    init {
        require(this.modules.zipWithNext().none { (left, right) -> left.id == right.id }) { "target module IDs must be unique" }
        require(this.addonBundles.zipWithNext().none { (left, right) -> left.identity.name == right.identity.name }) {
            "target addon bundle names must be unique"
        }
        require(this.addonBundles.size <= AddonGuestApiLimits.MAXIMUM_BUNDLES) { "target has too many addon bundles" }
        require(this.addonBundles.all { it.content.size <= AddonGuestApiLimits.MAXIMUM_BUNDLE_BYTES }) {
            "target addon bundle exceeds byte limit"
        }
        require(this.addonBundles.sumOf { it.content.size.toLong() } <= AddonGuestApiLimits.MAXIMUM_CATALOG_BYTES) {
            "target addon bundle catalog exceeds byte limit"
        }
        val advertised = this.modules.associateBy { it.id.value }
        require(this.addonBundles.all { bundle -> advertised[bundle.identity.name]?.contentHash == bundle.identity.hash }) {
            "target addon bundles must exactly match advertised modules"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is TargetCompileProfile &&
            toolchain == other.toolchain &&
            modules == other.modules &&
            limits == other.limits &&
            addonBundles == other.addonBundles

    override fun hashCode(): Int = listOf(toolchain, modules, limits, addonBundles).hashCode()
}

sealed interface ProfileResolution {
    data class Resolved(
        val profile: CompileProfile,
    ) : ProfileResolution

    sealed interface Failure : ProfileResolution {
        data class ToolchainMismatch(
            val expected: ToolchainLockIdentity,
            val available: ToolchainLockIdentity,
        ) : Failure

        data class MissingModule(
            val id: ModuleId,
        ) : Failure

        data class MissingAddon(
            val id: AddonId,
        ) : Failure

        data class MajorMismatch(
            val id: ModuleId,
            val expected: ApiMajor,
            val available: ApiMajor,
        ) : Failure

        data class VersionMismatch(
            val id: ModuleId,
            val expected: String,
            val available: String,
        ) : Failure

        data class ContentMismatch(
            val id: ModuleId,
            val expected: Hash256,
            val available: Hash256,
        ) : Failure

        data class AddonVersionMismatch(
            val id: AddonId,
            val expected: String,
            val available: String,
        ) : Failure

        data class AddonContentMismatch(
            val id: AddonId,
            val expected: Hash256,
            val available: Hash256,
        ) : Failure

        data class TargetLimitMismatch(
            val field: String,
            val required: Long,
            val available: Long,
        ) : Failure
    }
}
