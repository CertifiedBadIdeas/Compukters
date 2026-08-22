/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.compukters.compiler.worker.controller

import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompilationMetrics
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.CompileSuccess
import ru.lazyhat.compukters.compiler.worker.protocol.CompilerFailure
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.compiler.worker.protocol.TargetSettings
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundleIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CompilationIdentityTest {
    @Test
    fun `every semantic compiler input changes the cache identity`() {
        val base = request()
        val baseKey = CompilationIdentity.compute(base)
        val variants =
            listOf(
                base.copy(
                    sources =
                        listOf(
                            source("project/helper.kt", "val helper = 1"),
                            source("project/main.kt", "val answer = 43"),
                        ),
                ),
                base.copy(
                    sources =
                        listOf(
                            source("project/helper.kt", "val helper = 1"),
                            source("project/other.kt", "val answer = 42"),
                        ),
                ),
                base.copy(
                    target = TargetSettings.KOTLIN_2_4_JVM_17,
                    expectedIdentity = base.expectedIdentity.copy(languageVersion = "2.4-x"),
                ),
                base.copy(expectedIdentity = base.expectedIdentity.copy(compilerVersion = "2.4.11")),
                base.copy(expectedIdentity = base.expectedIdentity.copy(payloadHash = hash(1))),
                base.copy(expectedIdentity = base.expectedIdentity.copy(codegenAbi = 2u)),
                base.copy(expectedIdentity = base.expectedIdentity.copy(standardLibraryAbi = hash(2))),
                base.copy(expectedIdentity = base.expectedIdentity.copy(artifactWriterVersion = 2u)),
                base.copy(trustedApiBundles = listOf(TrustedBundleIdentity.of("api", hash(5)))),
                base.copy(trustedAddonBundles = listOf(TrustedBundleIdentity.of("addon", hash(6)))),
            )

        variants.forEach { variant -> assertNotEquals(baseKey, CompilationIdentity.compute(variant)) }
        assertEquals(baseKey, CompilationIdentity.compute(request()))
    }

    @Test
    fun `every request limit that can change admission or a bounded result changes identity`() {
        val base = request()
        val baseKey = CompilationIdentity.compute(base)
        val variants =
            mapOf(
                "sourceFiles" to base.limits.copy(sourceFiles = base.limits.sourceFiles - 1),
                "sourceFileBytes" to base.limits.copy(sourceFileBytes = base.limits.sourceFileBytes - 1),
                "sourceBytes" to base.limits.copy(sourceBytes = base.limits.sourceBytes - 1),
                "frameBytes" to base.limits.copy(frameBytes = base.limits.frameBytes - 1),
                "artifactBytes" to base.limits.copy(artifactBytes = base.limits.artifactBytes - 1),
                "diagnostics" to base.limits.copy(diagnostics = base.limits.diagnostics - 1),
                "diagnosticTextBytes" to base.limits.copy(diagnosticTextBytes = base.limits.diagnosticTextBytes - 1),
                "stderrBytes" to base.limits.copy(stderrBytes = base.limits.stderrBytes - 1),
                "temporaryBytes" to base.limits.copy(temporaryBytes = base.limits.temporaryBytes - 1),
                "temporaryFiles" to base.limits.copy(temporaryFiles = base.limits.temporaryFiles - 1),
            )

        variants.forEach { (name, limits) ->
            assertNotEquals(baseKey, CompilationIdentity.compute(base.copy(limits = limits)), name)
        }
    }

    @Test
    fun `cache publication admits only hash validated compiler success`() {
        val artifact = BinaryValue.of(byteArrayOf(1, 2, 3))
        val success = CompileSuccess(RequestId.of(1uL), artifact, sha256(artifact), emptyList(), metrics())

        assertNotNull(CompilationCacheArtifact.admit(success))
        assertNull(CompilationCacheArtifact.admit(success.copy(artifactHash = Hash256.zero())))
        assertNull(CompilationCacheArtifact.admit(CompilerFailure(RequestId.of(1uL), emptyList(), metrics())))
    }

    private fun request(): CompileRequest =
        CompileRequest(
            RequestId.of(1uL),
            listOf(source("project/helper.kt", "val helper = 1"), source("project/main.kt", "val answer = 42")),
            TargetSettings.KOTLIN_2_4_JVM_17,
            WorkerIdentity("2.4.10", "2.4", 1u, 1u, hash(3), hash(4)),
            WorkerLimits(),
        )

    private fun source(
        path: String,
        content: String,
    ) = ProjectSource(VirtualSourcePath.kotlin(path), BinaryValue.of(content.encodeToByteArray()))

    private fun hash(value: Byte): Hash256 = Hash256.of(ByteArray(32) { value })

    private fun sha256(value: BinaryValue): Hash256 = Hash256.of(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    private fun metrics() = CompilationMetrics(0uL, 0uL, 0uL)
}
