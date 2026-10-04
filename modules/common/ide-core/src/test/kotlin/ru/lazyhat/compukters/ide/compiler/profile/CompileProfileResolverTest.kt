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
import ru.lazyhat.compukters.ide.project.AddonId
import ru.lazyhat.compukters.ide.project.ProjectLock
import ru.lazyhat.compukters.ide.project.ProjectLockService
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import ru.lazyhat.compukters.platform.bundle.PlatformBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CompileProfileResolverTest {
    @Test
    fun `local profile requires every exact locked addon`() {
        val fixture = fixture()
        val missing = ProjectLock.of(fixture.toolchain, fixture.lock.addons.map { it.copy(id = AddonId("missing")) })

        assertEquals(
            ProfileResolution.Failure.MissingAddon(AddonId("missing")),
            fixture.resolver.resolveLocal(missing),
        )
    }

    @Test
    fun `local profile rejects toolchain and addon differences`() {
        val fixture = fixture()
        val changed = fixture.lock.addons.map { it.copy(version = "2.2.0") }

        assertIs<ProfileResolution.Failure.AddonVersionMismatch>(fixture.resolver.resolveLocal(ProjectLock.of(fixture.toolchain, changed)))
        assertIs<ProfileResolution.Failure.ToolchainMismatch>(
            CompileProfileResolver(fixture.toolchain.copy(artifactAbi = 9u), fixture.catalog, WorkerLimits()).resolveLocal(fixture.lock),
        )
    }

    @Test
    fun `target resolves exact advertised addon payload`() {
        val fixture = fixture()
        val modules = fixture.catalog.entries.map { it.identity }
        val addonBundles = fixture.catalog.addons.map { it.payload }
        assertIs<ProfileResolution.Resolved>(
            fixture.resolver.resolveTarget(fixture.lock, TargetCompileProfile(fixture.toolchain, modules, WorkerLimits(), addonBundles)),
        )

        assertIs<ProfileResolution.Failure.MissingAddon>(
            fixture.resolver.resolveTarget(
                fixture.lock,
                TargetCompileProfile(
                    fixture.toolchain,
                    fixture.catalog.entries
                        .filter {
                            it.identity.id.provider !=
                                "fixture"
                        }.map { it.identity },
                    WorkerLimits(),
                ),
            ),
        )
    }

    @Test
    fun `target rejects limits below compilation policy`() {
        val fixture = fixture(WorkerLimits(sourceFiles = 4))
        val target =
            TargetCompileProfile(
                fixture.toolchain,
                fixture.catalog.entries.map { it.identity },
                WorkerLimits(sourceFiles = 3),
                fixture.catalog.addons.map { it.payload },
            )

        assertEquals(
            ProfileResolution.Failure.TargetLimitMismatch("sourceFiles", 4, 3),
            fixture.resolver.resolveTarget(fixture.lock, target),
        )
    }

    @Test
    fun `resolved profile retains all built-ins and selected addons`() {
        val fixture = fixture()
        val profile = assertIs<ProfileResolution.Resolved>(fixture.resolver.resolveLocal(fixture.lock)).profile

        assertEquals(
            fixture.bundle.modules
                .map {
                    it.id.toString()
                }.toSet() + "fixture:api",
            profile.modules.map { it.identity.id.value }.toSet(),
        )
        assertEquals(listOf("fixture"), profile.addons.map { it.id.value })
        assertTrue(profile.modules.single { it.identity.id.value == "fixture:api" }.direct)
    }

    @Test
    fun `a target with two addons includes only project selected modules and matching payloads`() {
        val resolution = platformResolutionWithAddon(addonIds = listOf("create", "sable"))
        val catalog = resolution.catalog
        val target =
            TargetCompileProfile(
                resolution.toolchain,
                catalog.entries.map { it.identity },
                WorkerLimits(),
                catalog.addons.map { it.payload },
            )
        val resolver = CompileProfileResolver(resolution.toolchain, catalog, WorkerLimits())
        for (selected in listOf(emptySet(), setOf("create"), setOf("sable"), setOf("create", "sable"))) {
            val lock =
                ProjectLockService(NOOP_LOCK_WRITER).resolve(
                    ru.lazyhat.compukters.ide.project.ProjectManifest
                        .of("project", selected.mapTo(mutableSetOf(), ::AddonId)),
                    resolution,
                )
            val expectedModules =
                catalog.baseBundle.modules
                    .map { it.id.toString() }
                    .toSet() + selected.map { "$it:api" }
            for (result in listOf(resolver.resolveLocal(lock), resolver.resolveTarget(lock, target))) {
                val profile = assertIs<ProfileResolution.Resolved>(result).profile
                assertEquals(expectedModules, profile.modules.map { it.identity.id.value }.toSet(), selected.toString())
                assertEquals(selected, profile.addonBundles.map { it.identity.name }.toSet())
                assertEquals(selected, profile.addons.map { it.id.value }.toSet())
            }
        }
    }

    private fun fixture(requiredLimits: WorkerLimits = WorkerLimits()): Fixture {
        val resolution = platformResolutionWithAddon()
        val bundle = resolution.catalog.bundle
        val catalog = resolution.catalog
        val toolchain = resolution.toolchain
        val lock =
            ProjectLockService(
                NOOP_LOCK_WRITER,
            ).resolve(
                ru.lazyhat.compukters.ide.project.ProjectManifest
                    .of("fixture", setOf(AddonId("fixture"))),
                resolution,
            )
        return Fixture(bundle, catalog, toolchain, lock, CompileProfileResolver(toolchain, catalog, requiredLimits))
    }

    private data class Fixture(
        val bundle: PlatformBundle,
        val catalog: PlatformCatalog,
        val toolchain: ToolchainLockIdentity,
        val lock: ProjectLock,
        val resolver: CompileProfileResolver,
    )

    private companion object {
        val NOOP_LOCK_WRITER =
            object : ru.lazyhat.compukters.ide.project.LockFileWriter {
                override fun create(content: ByteArray) = Unit

                override fun update(content: ByteArray) = Unit
            }
    }
}
