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

import ru.lazyhat.compukters.ide.compiler.profile.platformResolutionWithAddon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProjectLockServiceTest {
    @Test
    fun `resolution locks only explicitly selected addons`() {
        val resolution = resolution()
        val lock = ProjectLockService(RecordingLockFileWriter()).resolve(manifest(), resolution)

        assertEquals(listOf("fixture"), lock.addons.map { it.id.value })
        assertEquals(lock, ProjectLockCodec.decode(ProjectLockCodec.encode(lock)))
    }

    @Test
    fun `declared unavailable addon fails while undeclared available addon stays out of lock`() {
        val service = ProjectLockService(RecordingLockFileWriter())
        val lock = service.resolve(ProjectManifest.of("hello", emptySet()), resolution())

        assertTrue(lock.addons.isEmpty())
        assertFailsWith<ProjectResolutionException> {
            service.resolve(ProjectManifest.of("hello", setOf(AddonId("missing"))), resolution())
        }
    }

    @Test
    fun `validation reports typed toolchain addon and manifest differences`() {
        val service = ProjectLockService(RecordingLockFileWriter())
        val resolution = resolution()
        val lock = service.resolve(manifest(), resolution)

        assertEquals(emptyList(), service.validate(manifest(), lock, resolution))
        val changedToolchain = resolution.copy(toolchain = resolution.toolchain.copy(artifactAbi = 9u))
        assertTrue(service.validate(manifest(), lock, changedToolchain).any { it is ProjectLockMismatch.Toolchain })

        val changedResolution = platformResolutionWithAddon(addonVersion = "2.0.0")
        val mismatches = service.validate(manifest(), lock, changedResolution)
        assertTrue(mismatches.any { it is ProjectLockMismatch.AddonVersion })
        assertTrue(mismatches.any { it is ProjectLockMismatch.AddonContent })

        val withoutAddon = ProjectLock.of(lock.toolchain, emptyList())
        assertIs<ProjectLockMismatch.ManifestAddonMissing>(
            service.validate(manifest(), withoutAddon, resolution).first { it is ProjectLockMismatch.ManifestAddonMissing },
        )

        val unexpected =
            ProjectLock.of(
                lock.toolchain,
                lock.addons + ResolvedAddon(AddonId("other"), "1.0.0", lock.addons.single().contentHash),
            )
        assertIs<ProjectLockMismatch.UnexpectedLockedAddon>(
            service.validate(manifest(), unexpected, resolution).first { it is ProjectLockMismatch.UnexpectedLockedAddon },
        )
    }

    @Test
    fun `create and update are distinct explicit persistence operations`() {
        val writer = RecordingLockFileWriter()
        val service = ProjectLockService(writer)
        val resolution = resolution()

        val created = service.createLock(manifest(), resolution)
        assertEquals(created, ProjectLockCodec.decode(writer.content!!.decodeToString()))
        assertFailsWith<IllegalStateException> { service.createLock(manifest(), resolution) }

        val updated = service.updateLock(manifest(), resolution)
        assertEquals(updated, ProjectLockCodec.decode(writer.content!!.decodeToString()))
        assertEquals(1, writer.createCalls)
        assertEquals(1, writer.updateCalls)
    }

    @Test
    fun `failed resolution and publication preserve prior lock bytes`() {
        val writer = RecordingLockFileWriter("prior".encodeToByteArray(), failUpdates = true)
        val service = ProjectLockService(writer)
        val before = writer.content!!.copyOf()

        assertFailsWith<ProjectResolutionException> {
            service.updateLock(ProjectManifest.of("hello", setOf(AddonId("missing"))), resolution())
        }
        assertTrue(before.contentEquals(writer.content))
        assertFailsWith<IllegalStateException> { service.updateLock(manifest(), resolution()) }
        assertTrue(before.contentEquals(writer.content))
    }

    private fun manifest() = ProjectManifest.of("hello", setOf(AddonId("fixture")))

    private fun resolution(): ProjectResolution = platformResolutionWithAddon()

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
