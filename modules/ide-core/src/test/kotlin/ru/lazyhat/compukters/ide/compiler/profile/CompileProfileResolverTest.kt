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

import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.project.ApiMajor
import ru.lazyhat.compukters.ide.project.ModuleId
import ru.lazyhat.compukters.ide.project.ProjectLock
import ru.lazyhat.compukters.ide.project.ResolvedModule
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CompileProfileResolverTest {
    @Test
    fun `local profile requires every exact locked module`() {
        val terminal = bundle("std:terminal", byteArrayOf(1), ApiBundleKind.API)
        val sensors = bundle("create:sensors", byteArrayOf(2), ApiBundleKind.ADDON)
        val lock = lock(terminal.module, sensors.module)
        val resolver = resolver(listOf(terminal))

        assertEquals(
            ProfileResolution.Failure.MissingModule(ModuleId.parse("create:sensors")),
            resolver.resolveLocal(lock),
        )
    }

    @Test
    fun `local profile rejects toolchain version and content differences`() {
        val terminal = bundle("std:terminal", byteArrayOf(1), ApiBundleKind.API)
        val changedVersion = terminal.copy(module = terminal.module.copy(version = "2.2.0"))
        val resolver = resolver(listOf(changedVersion))

        assertIs<ProfileResolution.Failure.VersionMismatch>(resolver.resolveLocal(lock(terminal.module)))
        assertIs<ProfileResolution.Failure.ToolchainMismatch>(
            resolver(toolchain = toolchain().copy(languageVersion = "2.5")).resolveLocal(lock(terminal.module)),
        )
    }

    @Test
    fun `target profile rejects same major with different content hash`() {
        val terminal = bundle("std:terminal", byteArrayOf(1), ApiBundleKind.API)
        val lock = lock(terminal.module)
        val changed = terminal.module.copy(contentHash = hash(byteArrayOf(9)))
        val target = TargetCompileProfile(toolchain(), listOf(changed), WorkerLimits())

        assertIs<ProfileResolution.Failure.ContentMismatch>(resolver(listOf(terminal)).resolveTarget(lock, target))
    }

    @Test
    fun `target rejects limits below required compilation policy`() {
        val terminal = bundle("std:terminal", byteArrayOf(1), ApiBundleKind.API)
        val required = WorkerLimits(sourceFiles = 4)
        val target = TargetCompileProfile(toolchain(), listOf(terminal.module), required.copy(sourceFiles = 3))

        assertEquals(
            ProfileResolution.Failure.TargetLimitMismatch("sourceFiles", 4, 3),
            resolver(listOf(terminal), requiredLimits = required).resolveTarget(lock(terminal.module), target),
        )
    }

    @Test
    fun `resolved profile separates canonical API and addon bundles`() {
        val terminal = bundle("std:terminal", byteArrayOf(1), ApiBundleKind.API)
        val sensors = bundle("create:sensors", byteArrayOf(2), ApiBundleKind.ADDON)
        val limits = WorkerLimits(sourceFiles = 4)
        val resolution =
            assertIs<ProfileResolution.Resolved>(
                resolver(listOf(terminal, sensors), requiredLimits = limits).resolveLocal(lock(sensors.module, terminal.module)),
            )

        assertEquals(listOf("std:terminal"), resolution.profile.apiBundles.map { it.module.id.value })
        assertEquals(listOf("create:sensors"), resolution.profile.addonBundles.map { it.module.id.value })
        assertEquals(limits, resolution.profile.limits)
    }

    @Test
    fun `compile profile rejects duplicate IDs across bundle kinds`() {
        val terminal = bundle("std:terminal", byteArrayOf(1), ApiBundleKind.API)

        assertFailsWith<IllegalArgumentException> {
            CompileProfile(
                toolchain(),
                listOf(terminal),
                listOf(terminal.copy(kind = ApiBundleKind.ADDON)),
                WorkerLimits(),
            )
        }
    }

    private fun resolver(
        bundles: List<ResolvedApiBundle> = emptyList(),
        toolchain: ToolchainLockIdentity = toolchain(),
        requiredLimits: WorkerLimits = WorkerLimits(),
    ) = CompileProfileResolver(toolchain, GuestApiBundleCatalog.of(bundles), requiredLimits)

    private fun lock(vararg modules: ResolvedModule) = ProjectLock.of(toolchain(), modules.toList())

    private fun bundle(
        id: String,
        bytes: ByteArray,
        kind: ApiBundleKind,
    ) = ResolvedApiBundle(
        ResolvedModule(ModuleId.parse(id), ApiMajor(2), "2.1.0", hash(bytes)),
        kind,
        BinaryValue.of(bytes),
    )

    private fun toolchain() = ToolchainLockIdentity("2.4.10", "2.4", 1u, 1u, 1u, hash(byteArrayOf(3)), hash(byteArrayOf(4)))

    private fun hash(bytes: ByteArray) = Hash256.of(MessageDigest.getInstance("SHA-256").digest(bytes))
}
