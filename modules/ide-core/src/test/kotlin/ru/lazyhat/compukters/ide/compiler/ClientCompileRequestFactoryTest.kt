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

package ru.lazyhat.compukters.ide.compiler

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.TargetSettings
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import ru.lazyhat.compukters.ide.compiler.profile.ApiBundleKind
import ru.lazyhat.compukters.ide.compiler.profile.CompileProfile
import ru.lazyhat.compukters.ide.compiler.profile.ResolvedApiBundle
import ru.lazyhat.compukters.ide.project.ApiMajor
import ru.lazyhat.compukters.ide.project.ModuleId
import ru.lazyhat.compukters.ide.project.ResolvedModule
import ru.lazyhat.compukters.ide.project.ToolchainLockIdentity
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ClientCompileRequestFactoryTest {
    @Test
    fun `factory creates canonical worker request from exact profile`() {
        val prepared = ClientCompileRequestFactory.prepare(baseline())

        assertEquals(listOf("std:terminal"), prepared.request.trustedApiBundles.map { it.name })
        assertEquals(listOf("create:sensors"), prepared.request.trustedAddonBundles.map { it.name })
        assertEquals(SourceSnapshotIdentity.of(baseline().sources), prepared.sourceSnapshotId)
        assertEquals(baseline().profile.toolchain.payloadHash, prepared.request.expectedIdentity.payloadHash)
        assertEquals(TargetSettings.KOTLIN_2_4_JVM_17, prepared.request.target)
    }

    @Test
    fun `every semantic client input changes compilation identity`() {
        val baseline = baseline()
        val identity = ClientCompileRequestFactory.prepare(baseline).identity
        val changedLimits =
            listOf(
                baseline.limits().copy(sourceFiles = baseline.limits().sourceFiles + 1),
                baseline.limits().copy(sourceFileBytes = baseline.limits().sourceFileBytes + 1),
                baseline.limits().copy(sourceBytes = baseline.limits().sourceBytes + 1),
                baseline.limits().copy(frameBytes = baseline.limits().frameBytes + 1),
                baseline.limits().copy(artifactBytes = baseline.limits().artifactBytes + 1),
                baseline.limits().copy(diagnostics = baseline.limits().diagnostics + 1),
                baseline.limits().copy(diagnosticTextBytes = baseline.limits().diagnosticTextBytes + 1),
                baseline.limits().copy(stderrBytes = baseline.limits().stderrBytes + 1),
                baseline.limits().copy(temporaryBytes = baseline.limits().temporaryBytes + 1),
                baseline.limits().copy(temporaryFiles = baseline.limits().temporaryFiles + 1),
            )
        val variants =
            buildList {
                add(baseline.copy(sources = snapshot("fun main() = 2", baseline.limits())))
                add(baseline.copy(manifestBytes = BinaryValue.of("changed manifest".encodeToByteArray())))
                add(baseline.copy(lockBytes = BinaryValue.of("changed lock".encodeToByteArray())))
                add(baseline.copy(profile = baseline.profile.withToolchain(baseline.profile.toolchain.copy(artifactAbi = 9u))))
                add(baseline.copy(profile = baseline.profile.withChangedApiHash()))
                changedLimits.forEach { limits -> add(baseline.copy(profile = baseline.profile.withLimits(limits))) }
            }

        variants.forEach { variant ->
            assertNotEquals(
                identity,
                ClientCompileRequestFactory.prepare(variant).identity,
                "variant must have a distinct identity: $variant",
            )
        }
    }

    private fun baseline(): ClientBuildSnapshot {
        val limits = WorkerLimits(sourceFiles = 4, sourceFileBytes = 1024, sourceBytes = 2048)
        val api = bundle("std:terminal", ApiBundleKind.API, byteArrayOf(1))
        val addon = bundle("create:sensors", ApiBundleKind.ADDON, byteArrayOf(2))
        return ClientBuildSnapshot(
            snapshot("fun main() = 1", limits),
            BinaryValue.of("manifest".encodeToByteArray()),
            BinaryValue.of("lock".encodeToByteArray()),
            CompileProfile(toolchain(), listOf(api), listOf(addon), limits),
            TargetSettings.KOTLIN_2_4_JVM_17,
        )
    }

    private fun ClientBuildSnapshot.limits() = profile.limits

    private fun CompileProfile.withLimits(limits: WorkerLimits) = CompileProfile(toolchain, apiBundles, addonBundles, limits)

    private fun CompileProfile.withToolchain(toolchain: ToolchainLockIdentity) = CompileProfile(toolchain, apiBundles, addonBundles, limits)

    private fun CompileProfile.withChangedApiHash(): CompileProfile {
        val changed = apiBundles.single().copy(module = apiBundles.single().module.copy(contentHash = hash(byteArrayOf(9))))
        return CompileProfile(toolchain, listOf(changed), addonBundles, limits)
    }

    private fun snapshot(
        source: String,
        limits: WorkerLimits,
    ) = ProjectSnapshot.of(
        listOf(ProjectSource(VirtualSourcePath.kotlin("project/main.kt"), BinaryValue.of(source.encodeToByteArray()))),
        limits,
    )

    private fun bundle(
        id: String,
        kind: ApiBundleKind,
        bytes: ByteArray,
    ) = ResolvedApiBundle(
        ResolvedModule(ModuleId.parse(id), ApiMajor(2), "2.1.0", hash(bytes)),
        kind,
        BinaryValue.of(bytes),
    )

    private fun toolchain() = ToolchainLockIdentity("2.4.10", "2.4", 1u, 1u, 1u, hash(byteArrayOf(3)), hash(byteArrayOf(4)))

    private fun hash(bytes: ByteArray) = Hash256.of(MessageDigest.getInstance("SHA-256").digest(bytes))
}
