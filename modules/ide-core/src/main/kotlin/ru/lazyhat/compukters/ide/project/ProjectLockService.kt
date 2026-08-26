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

    data class ManifestModuleMissing(
        val id: ModuleId,
    ) : ProjectLockMismatch

    data class ManifestModuleMajor(
        val id: ModuleId,
        val required: ApiMajor,
        val locked: ApiMajor,
    ) : ProjectLockMismatch

    data class UnexpectedLockedModule(
        val id: ModuleId,
    ) : ProjectLockMismatch

    data class ModuleUnavailable(
        val id: ModuleId,
    ) : ProjectLockMismatch

    data class ModuleMajor(
        val id: ModuleId,
        val expected: ApiMajor,
        val available: ApiMajor,
    ) : ProjectLockMismatch

    data class ModuleVersion(
        val id: ModuleId,
        val expected: String,
        val available: String,
    ) : ProjectLockMismatch

    data class ModuleContent(
        val id: ModuleId,
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
        val required = manifest.modules
        val available = resolution.modules.associateBy(ResolvedModule::id)
        if (available.keys != required.keys) {
            val missing = required.keys - available.keys
            val extra = available.keys - required.keys
            throw ProjectResolutionException(
                "resolution does not exactly match manifest; missing=${missing.joinToString { it.value }}, " +
                    "extra=${extra.joinToString { it.value }}",
            )
        }
        required.forEach { (id, major) ->
            val resolved = available.getValue(id)
            if (resolved.major != major) {
                throw ProjectResolutionException("module ${id.value} requires major ${major.value}, resolved ${resolved.major.value}")
            }
        }
        return ProjectLock.of(resolution.toolchain, resolution.modules)
    }

    fun validate(
        manifest: ProjectManifest,
        lock: ProjectLock,
        availableProfile: ProjectResolution,
    ): List<ProjectLockMismatch> =
        buildList {
            compareToolchain(lock.toolchain, availableProfile.toolchain)
            val locked = lock.modules.associateBy(ResolvedModule::id)
            manifest.modules.forEach { (id, requiredMajor) ->
                val lockedModule = locked[id]
                if (lockedModule == null) {
                    add(ProjectLockMismatch.ManifestModuleMissing(id))
                } else if (lockedModule.major != requiredMajor) {
                    add(ProjectLockMismatch.ManifestModuleMajor(id, requiredMajor, lockedModule.major))
                }
            }
            (locked.keys - manifest.modules.keys).forEach { add(ProjectLockMismatch.UnexpectedLockedModule(it)) }
            val available = availableProfile.modules.associateBy(ResolvedModule::id)
            lock.modules.forEach { expected ->
                val actual = available[expected.id]
                if (actual == null) {
                    add(ProjectLockMismatch.ModuleUnavailable(expected.id))
                } else {
                    if (actual.major != expected.major) {
                        add(ProjectLockMismatch.ModuleMajor(expected.id, expected.major, actual.major))
                    }
                    if (actual.version != expected.version) {
                        add(ProjectLockMismatch.ModuleVersion(expected.id, expected.version, actual.version))
                    }
                    if (actual.contentHash != expected.contentHash) {
                        add(ProjectLockMismatch.ModuleContent(expected.id, expected.contentHash.hex(), actual.contentHash.hex()))
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
        compare("stdlib_abi_sha256", expected.standardLibraryAbi.hex(), available.standardLibraryAbi.hex())
    }

    private fun MutableList<ProjectLockMismatch>.compare(
        field: String,
        expected: String,
        available: String,
    ) {
        if (expected != available) add(ProjectLockMismatch.Toolchain(field, expected, available))
    }
}
