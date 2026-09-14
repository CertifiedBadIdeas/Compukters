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

import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.project.ProjectLock
import ru.lazyhat.compukters.ide.project.ResolvedAddon
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity

class CompileProfileResolver(
    private val localToolchain: ToolchainLockIdentity,
    private val catalog: PlatformCatalog,
    private val requiredLimits: WorkerLimits,
) {
    fun resolveLocal(lock: ProjectLock): ProfileResolution {
        if (lock.toolchain != localToolchain) return ProfileResolution.Failure.ToolchainMismatch(lock.toolchain, localToolchain)
        return resolveBundles(lock, requiredLimits, catalog)
    }

    fun resolveTarget(
        lock: ProjectLock,
        target: TargetCompileProfile,
    ): ProfileResolution {
        if (lock.toolchain != target.toolchain) return ProfileResolution.Failure.ToolchainMismatch(lock.toolchain, target.toolchain)
        compareLimits(requiredLimits, target.limits)?.let { return it }
        if (lock.toolchain != localToolchain) return ProfileResolution.Failure.ToolchainMismatch(lock.toolchain, localToolchain)
        val targetCatalog = PlatformCatalog.forTarget(catalog.baseBundle, target.modules, target.addonBundles)
        return resolveBundles(lock, target.limits, targetCatalog)
    }

    private fun resolveBundles(
        lock: ProjectLock,
        limits: WorkerLimits,
        availableCatalog: PlatformCatalog,
    ): ProfileResolution {
        lock.addons.forEach { expected ->
            val available = availableCatalog.findAddon(expected.id)?.identity ?: return ProfileResolution.Failure.MissingAddon(expected.id)
            compareAddon(expected, available)?.let { return it }
        }
        val selected =
            try {
                availableCatalog.resolve(lock.addons.mapTo(mutableSetOf()) { it.id })
            } catch (failure: IllegalArgumentException) {
                return ProfileResolution.Failure.MissingAddon(lock.addons.first().id)
            }
        return ProfileResolution.Resolved(
            CompileProfile(
                lock.toolchain,
                availableCatalog.bundle.identity,
                lock.addons,
                selected.modules,
                limits,
                availableCatalog.addonBundlesFor(lock.addons.mapTo(mutableSetOf()) { it.id }),
            ),
        )
    }

    private fun compareAddon(
        expected: ResolvedAddon,
        available: ResolvedAddon,
    ): ProfileResolution.Failure? =
        when {
            expected.version != available.version -> {
                ProfileResolution.Failure.AddonVersionMismatch(expected.id, expected.version, available.version)
            }

            expected.contentHash != available.contentHash -> {
                ProfileResolution.Failure.AddonContentMismatch(expected.id, expected.contentHash, available.contentHash)
            }

            else -> {
                null
            }
        }
}

private fun compareLimits(
    required: WorkerLimits,
    available: WorkerLimits,
): ProfileResolution.Failure.TargetLimitMismatch? {
    val fields =
        listOf(
            LimitField("sourceFiles", required.sourceFiles.toLong(), available.sourceFiles.toLong()),
            LimitField("sourceFileBytes", required.sourceFileBytes.toLong(), available.sourceFileBytes.toLong()),
            LimitField("sourceBytes", required.sourceBytes.toLong(), available.sourceBytes.toLong()),
            LimitField("frameBytes", required.frameBytes.toLong(), available.frameBytes.toLong()),
            LimitField("artifactBytes", required.artifactBytes.toLong(), available.artifactBytes.toLong()),
            LimitField("diagnostics", required.diagnostics.toLong(), available.diagnostics.toLong()),
            LimitField("diagnosticTextBytes", required.diagnosticTextBytes.toLong(), available.diagnosticTextBytes.toLong()),
            LimitField("stderrBytes", required.stderrBytes.toLong(), available.stderrBytes.toLong()),
            LimitField("temporaryBytes", required.temporaryBytes, available.temporaryBytes),
            LimitField("temporaryFiles", required.temporaryFiles.toLong(), available.temporaryFiles.toLong()),
        )
    val mismatch = fields.firstOrNull { field -> field.available < field.required } ?: return null
    return ProfileResolution.Failure.TargetLimitMismatch(mismatch.name, mismatch.required, mismatch.available)
}

private data class LimitField(
    val name: String,
    val required: Long,
    val available: Long,
)
