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

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.project.ApiMajor
import ru.lazyhat.compukters.ide.project.ModuleId
import ru.lazyhat.compukters.ide.project.ResolvedModule
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import java.util.Collections

class CompileProfile(
    val toolchain: ToolchainLockIdentity,
    apiBundles: List<ResolvedApiBundle>,
    addonBundles: List<ResolvedApiBundle>,
    val limits: WorkerLimits,
) {
    val apiBundles: List<ResolvedApiBundle> = Collections.unmodifiableList(apiBundles.sortedWith(API_BUNDLE_COMPARATOR))
    val addonBundles: List<ResolvedApiBundle> = Collections.unmodifiableList(addonBundles.sortedWith(API_BUNDLE_COMPARATOR))

    init {
        require(this.apiBundles.all { bundle -> bundle.kind == ApiBundleKind.API }) { "API bundle list contains an add-on" }
        require(this.addonBundles.all { bundle -> bundle.kind == ApiBundleKind.ADDON }) { "add-on bundle list contains an API" }
        val ids = (this.apiBundles + this.addonBundles).map { bundle -> bundle.module.id }
        require(ids.size == ids.toSet().size) { "compile profile bundle IDs must be unique" }
    }
}

class TargetCompileProfile(
    val toolchain: ToolchainLockIdentity,
    modules: List<ResolvedModule>,
    val limits: WorkerLimits,
) {
    val modules: List<ResolvedModule> =
        Collections.unmodifiableList(
            modules.sortedWith {
                left,
                right,
                ->
                compareModuleIds(left.id, right.id)
            },
        )

    init {
        require(this.modules.zipWithNext().none { (left, right) -> left.id == right.id }) { "target module IDs must be unique" }
    }
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

        data class UnexpectedModule(
            val id: ModuleId,
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

        data class TargetLimitMismatch(
            val field: String,
            val required: Long,
            val available: Long,
        ) : Failure
    }
}
