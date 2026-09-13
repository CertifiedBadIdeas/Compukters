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

package ru.lazyhat.compukters.integration.create

import ru.lazyhat.compukters.addon.api.AddonGuestApiBundleCodec
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.k2.K2CompilerAdapter
import ru.lazyhat.compukters.compiler.worker.k2.K2CompilerInputs
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.compiler.worker.protocol.TargetSettings
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundleIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundlePayload
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.platform.bundle.PlatformBundleCodec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CreateKineticsArtifactTest {
    @Test
    fun `Create kinetics program lowers deterministically for GameTest conformance`() {
        val platform =
            PlatformBundleCodec.decode(
                checkNotNull(K2CompilerAdapter::class.java.getResourceAsStream("/compukters-platform/compukters-platform.cpb")) {
                    "compiler worker contains no packaged platform bundle"
                }.use { it.readAllBytes() },
            )
        val addonBytes = Files.readAllBytes(Path.of(checkNotNull(System.getProperty("compukters.test.createKineticsGuestApi"))))
        val addon = AddonGuestApiBundleCodec.decode(addonBytes)
        val identity =
            WorkerIdentity(
                "2.4.10",
                platform.identity.languageVersion,
                1u,
                1u,
                Hash256.zero(),
                Hash256.of(platform.identity.contentHash.toByteArray()),
            )
        val addonIdentity = TrustedBundleIdentity.of(addon.identity.module, Hash256.of(addon.identity.contentHash.toByteArray()))
        val selectedModules =
            platform.modules.map { module ->
                TrustedBundleIdentity.of(
                    module.id.toString(),
                    Hash256.of(PlatformBundleCodec.moduleContentHash(module).toByteArray()),
                )
            } + addonIdentity
        val source =
            """
            import create.kinetics.Kinetics

            fun main() {
                val left = Kinetics.left.speedometer()
                val right = Kinetics.right.speedometer()
                val back = Kinetics.back.speedometer()
                val stressometer = Kinetics.bottom.stressometer()
                val controller = Kinetics.top.rotationController()
                println(controller.setTargetSpeed(32_000))
                println(left.awaitSpeedChange())
                println(right.awaitSpeedChange())
                println(back.awaitSpeedChange())
                stressometer.awaitChange()
                println(stressometer.stress())
                println(stressometer.capacity())
                val stale = Kinetics.front.speedometer()
                println("waiting-disconnect")
                stale.awaitSpeedChange()
                println("unexpected-rebind")
            }
            """.trimIndent()
        val request =
            CompileRequest(
                RequestId.of(1u),
                listOf(ProjectSource(VirtualSourcePath.kotlin("project/main.kt"), BinaryValue.of(source.encodeToByteArray()))),
                TargetSettings.KOTLIN_2_4_JVM_17,
                identity,
                WorkerLimits(),
                selectedModules,
                listOf(TrustedBundlePayload(addonIdentity, BinaryValue.of(addonBytes))),
            )
        val temporaryRoot = createTempDirectory("compukters-create-artifact-test-")
        try {
            val adapter =
                K2CompilerAdapter(
                    K2CompilerInputs(
                        temporaryRoot,
                        Path.of(checkNotNull(System.getProperty("compukters.test.compilerWorkerJar"))),
                        identity,
                    ),
                    platform,
                )
            val first = adapter.compile(request)
            val second = adapter.compile(request)
            val artifact = assertNotNull(first.artifact, first.diagnostics.joinToString()).toByteArray()
            assertContentEquals(artifact, assertNotNull(second.artifact).toByteArray())
            assertTrue(first.diagnostics.none { it.severity.name == "ERROR" }, first.diagnostics.toString())
            System.getProperty("compukter.vm.createKineticsArtifact")?.let { output ->
                Path.of(output).also { it.parent.createDirectories() }.writeBytes(artifact)
            }
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }
}
