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
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProjectDependencyServiceTest {
    @Test
    fun `addon enablement publishes canonical manifest and lock and can roll back`() {
        val root = createTempDirectory("compukters-dependency-")
        val project = ProjectCatalog.open(root).create("hello")
        val resolution = resolution()
        val addon =
            resolution.catalog.addons
                .single()
                .identity
        val service = ProjectDependencyService(project.handle, resolution)

        val published =
            assertIs<ProjectDependencyUpdate.Published>(
                service.enableAddon(addon.id),
            )
        val manifestPath = project.handle.canonicalPath.resolve("compukter.toml")
        val lockPath = project.handle.canonicalPath.resolve("compukter.lock")
        val manifest = ProjectManifestCodec.decode(Files.readString(manifestPath))

        assertTrue(addon.id in manifest.addons)
        assertEquals(ProjectManifestCodec.encode(manifest), Files.readString(manifestPath))
        assertTrue(Files.isRegularFile(lockPath))
        assertEquals(ProjectDependencyUpdate.AlreadyDirect, service.enableAddon(addon.id))

        assertEquals(ProjectDependencyRollback.Restored, service.rollback(published.receipt))
        assertTrue(ProjectManifestCodec.decode(Files.readString(manifestPath)).addons.isEmpty())
        assertTrue(Files.notExists(lockPath))
    }

    @Test
    fun `rollback refuses to overwrite a newer manifest`() {
        val root = createTempDirectory("compukters-dependency-conflict-")
        val project = ProjectCatalog.open(root).create("hello")
        val resolution = resolution()
        val addon =
            resolution.catalog.addons
                .single()
                .identity
        val service = ProjectDependencyService(project.handle, resolution)
        val receipt = assertIs<ProjectDependencyUpdate.Published>(service.enableAddon(addon.id)).receipt
        val manifestPath = project.handle.canonicalPath.resolve("compukter.toml")

        Files.writeString(manifestPath, "newer")

        assertIs<ProjectDependencyRollback.Conflict>(service.rollback(receipt))
        assertEquals("newer", Files.readString(manifestPath))
    }

    @Test
    fun `addon enablement validates proposed lock before publishing`() {
        val root = createTempDirectory("compukters-dependency-validation-")
        val project = ProjectCatalog.open(root).create("hello")
        val resolution = resolution()
        val addon =
            resolution.catalog.addons
                .single()
                .identity
        val service = ProjectDependencyService(project.handle, resolution)

        val result =
            service.enableAddon(addon.id) { proposed ->
                val locked = proposed.addons.single { it.id == addon.id }
                assertEquals(addon, locked)
                "target profile changed"
            }

        assertEquals(ProjectDependencyUpdate.Conflict("target profile changed"), result)
        assertTrue(ProjectManifestCodec.decode(Files.readString(project.handle.canonicalPath.resolve("compukter.toml"))).addons.isEmpty())
        assertTrue(Files.notExists(project.handle.canonicalPath.resolve("compukter.lock")))
    }

    private fun resolution(): ProjectResolution = platformResolutionWithAddon()
}
