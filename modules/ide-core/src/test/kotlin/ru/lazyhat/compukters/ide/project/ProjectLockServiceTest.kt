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

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProjectLockServiceTest {
    @Test
    fun `resolution requires exactly one compatible module per manifest requirement`() {
        val service = ProjectLockService(RecordingLockFileWriter())
        val manifest = manifest()
        val exact = resolution(module("std:terminal", 2, "2.1.0", 3))

        assertEquals(ProjectLock.of(exact.toolchain, exact.modules), service.resolve(manifest, exact))
        assertFailsWith<ProjectResolutionException> { service.resolve(manifest, resolution()) }
        assertFailsWith<ProjectResolutionException> {
            service.resolve(manifest, resolution(module("std:terminal", 1, "1.9.0", 3)))
        }
        assertFailsWith<ProjectResolutionException> {
            service.resolve(
                manifest,
                resolution(module("std:terminal", 2, "2.1.0", 3), module("create:kinetics", 1, "1.0", 4)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProjectResolution(toolchain(), listOf(module("std:terminal", 2, "2.1.0", 3), module("std:terminal", 2, "2.2.0", 4)))
        }
    }

    @Test
    fun `validation reports typed toolchain and module differences`() {
        val service = ProjectLockService(RecordingLockFileWriter())
        val manifest = manifest()
        val lockedProfile = resolution(module("std:terminal", 2, "2.1.0", 3))
        val lock = service.resolve(manifest, lockedProfile)

        assertEquals(emptyList(), service.validate(manifest, lock, lockedProfile))

        val changedToolchain =
            lockedProfile.copy(toolchain = toolchain().copy(languageVersion = "2.5", artifactAbi = 9u))
        val toolchainMismatches = service.validate(manifest, lock, changedToolchain)
        assertTrue(toolchainMismatches.any { it is ProjectLockMismatch.Toolchain && it.field == "language" })
        assertTrue(toolchainMismatches.any { it is ProjectLockMismatch.Toolchain && it.field == "artifact_abi" })

        val changedModule = resolution(module("std:terminal", 2, "2.2.0", 9))
        val moduleMismatches = service.validate(manifest, lock, changedModule)
        assertTrue(moduleMismatches.any { it is ProjectLockMismatch.ModuleVersion })
        assertTrue(moduleMismatches.any { it is ProjectLockMismatch.ModuleContent })

        assertIs<ProjectLockMismatch.ModuleUnavailable>(service.validate(manifest, lock, resolution()).single())
        assertTrue(
            service.validate(manifest, lock, resolution(module("std:terminal", 1, "1.0", 3))).any {
                it is ProjectLockMismatch.ModuleMajor
            },
        )
        val staleLock = ProjectLock.of(toolchain(), listOf(module("std:terminal", 1, "1.0", 3)))
        assertTrue(service.validate(manifest, staleLock, lockedProfile).any { it is ProjectLockMismatch.ManifestModuleMajor })
    }

    @Test
    fun `create and update are distinct explicit persistence operations`() {
        val writer = RecordingLockFileWriter()
        val service = ProjectLockService(writer)
        val manifest = manifest()
        val first = resolution(module("std:terminal", 2, "2.1.0", 3))
        val second = resolution(module("std:terminal", 2, "2.2.0", 4))

        val created = service.createLock(manifest, first)
        assertEquals(created, ProjectLockCodec.decode(writer.content!!.decodeToString()))
        assertFailsWith<IllegalStateException> { service.createLock(manifest, first) }
        assertEquals(created, ProjectLockCodec.decode(writer.content!!.decodeToString()))

        val updated = service.updateLock(manifest, second)
        assertEquals(updated, ProjectLockCodec.decode(writer.content!!.decodeToString()))
        assertEquals(1, writer.createCalls)
        assertEquals(1, writer.updateCalls)

        val beforeValidation = writer.content!!.copyOf()
        service.validate(manifest, updated, second)
        assertTrue(beforeValidation.contentEquals(writer.content))
    }

    @Test
    fun `failed resolution and failed publication preserve prior lock bytes`() {
        val writer = RecordingLockFileWriter("prior".encodeToByteArray(), failUpdates = true)
        val service = ProjectLockService(writer)
        val before = writer.content!!.copyOf()

        assertFailsWith<ProjectResolutionException> { service.updateLock(manifest(), resolution()) }
        assertTrue(before.contentEquals(writer.content))
        assertFailsWith<IllegalStateException> {
            service.updateLock(manifest(), resolution(module("std:terminal", 2, "2.1.0", 3)))
        }
        assertTrue(before.contentEquals(writer.content))
    }

    private fun manifest() = ProjectManifest.of("hello", mapOf(ModuleId("std", "terminal") to ApiMajor(2)))

    private fun resolution(vararg modules: ResolvedModule) = ProjectResolution(toolchain(), modules.toList())

    private fun toolchain() =
        ToolchainLockIdentity(
            compilerVersion = "2.4.10",
            languageVersion = "2.4",
            codegenAbi = 1u,
            artifactAbi = 2u,
            artifactWriterVersion = 3u,
            payloadHash = hash(1),
            standardLibraryAbi = hash(2),
        )

    private fun module(
        id: String,
        major: Int,
        version: String,
        hashByte: Int,
    ) = ResolvedModule(ModuleId.parse(id), ApiMajor(major), version, hash(hashByte))

    private fun hash(byte: Int) = Hash256.of(ByteArray(32) { byte.toByte() })

    private class RecordingLockFileWriter(
        var content: ByteArray? = null,
        private val failUpdates: Boolean = false,
    ) : LockFileWriter {
        var createCalls = 0
        var updateCalls = 0

        override fun create(content: ByteArray) {
            check(this.content == null) { "lock already exists" }
            createCalls++
            this.content = content.copyOf()
        }

        override fun update(content: ByteArray) {
            check(this.content != null) { "lock does not exist" }
            updateCalls++
            if (failUpdates) error("injected update failure")
            this.content = content.copyOf()
        }
    }
}
