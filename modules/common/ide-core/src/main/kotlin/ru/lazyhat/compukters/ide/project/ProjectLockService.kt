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

package ru.lazyhat.compukters.ide.project

class ProjectResolutionException(
    message: String,
) : IllegalArgumentException(message)

sealed interface ProjectLockMismatch {
    data class Toolchain(
        val field: String,
        val expected: String,
        val available: String,
    ) : ProjectLockMismatch

    data class ManifestAddonMissing(
        val id: AddonId,
    ) : ProjectLockMismatch

    data class UnexpectedLockedAddon(
        val id: AddonId,
    ) : ProjectLockMismatch

    data class AddonUnavailable(
        val id: AddonId,
    ) : ProjectLockMismatch

    data class AddonVersion(
        val id: AddonId,
        val expected: String,
        val available: String,
    ) : ProjectLockMismatch

    data class AddonContent(
        val id: AddonId,
        val expected: String,
        val available: String,
    ) : ProjectLockMismatch
}

interface LockFileWriter {
    fun create(content: ByteArray)

    fun update(content: ByteArray)
}

class ProjectLockService(
    private val writer: LockFileWriter,
) {
    fun resolve(
        manifest: ProjectManifest,
        resolution: ProjectResolution,
    ): ProjectLock {
        val selected =
            try {
                resolution.catalog.resolve(manifest.addons)
            } catch (failure: IllegalArgumentException) {
                throw ProjectResolutionException(failure.message ?: "addon resolution failed")
            }
        return ProjectLock.of(
            resolution.toolchain,
            selected.addons,
        )
    }

    fun validate(
        manifest: ProjectManifest,
        lock: ProjectLock,
        availableProfile: ProjectResolution,
    ): List<ProjectLockMismatch> =
        buildList {
            compareToolchain(lock.toolchain, availableProfile.toolchain)
            val locked = lock.addons.associateBy(ResolvedAddon::id)
            manifest.addons.forEach { id ->
                if (id !in locked) {
                    add(ProjectLockMismatch.ManifestAddonMissing(id))
                }
            }
            lock.addons.filter { it.id !in manifest.addons }.forEach {
                add(ProjectLockMismatch.UnexpectedLockedAddon(it.id))
            }
            val available = availableProfile.catalog.addons.associateBy { it.identity.id }
            lock.addons.forEach { expected ->
                val actual = available[expected.id]?.identity
                if (actual == null) {
                    add(ProjectLockMismatch.AddonUnavailable(expected.id))
                } else {
                    if (actual.version != expected.version) {
                        add(ProjectLockMismatch.AddonVersion(expected.id, expected.version, actual.version))
                    }
                    if (actual.contentHash != expected.contentHash) {
                        add(ProjectLockMismatch.AddonContent(expected.id, expected.contentHash.hex(), actual.contentHash.hex()))
                    }
                }
            }
        }

    fun createLock(
        manifest: ProjectManifest,
        resolution: ProjectResolution,
    ): ProjectLock = resolve(manifest, resolution).also { writer.create(ProjectLockCodec.encode(it).encodeToByteArray()) }

    fun updateLock(
        manifest: ProjectManifest,
        resolution: ProjectResolution,
    ): ProjectLock = resolve(manifest, resolution).also { writer.update(ProjectLockCodec.encode(it).encodeToByteArray()) }

    private fun MutableList<ProjectLockMismatch>.compareToolchain(
        expected: ToolchainLockIdentity,
        available: ToolchainLockIdentity,
    ) {
        compare("compiler", expected.compilerVersion, available.compilerVersion)
        compare("language", expected.languageVersion, available.languageVersion)
        compare("codegen_abi", expected.codegenAbi.toString(), available.codegenAbi.toString())
        compare("artifact_abi", expected.artifactAbi.toString(), available.artifactAbi.toString())
        compare("artifact_writer", expected.artifactWriterVersion.toString(), available.artifactWriterVersion.toString())
        compare("payload_sha256", expected.payloadHash.hex(), available.payloadHash.hex())
        compare("platform_abi_sha256", expected.platformAbi.hex(), available.platformAbi.hex())
    }

    private fun MutableList<ProjectLockMismatch>.compare(
        field: String,
        expected: String,
        available: String,
    ) {
        if (expected != available) add(ProjectLockMismatch.Toolchain(field, expected, available))
    }
}
