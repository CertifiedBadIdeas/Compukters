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

package ru.lazyhat.compukters.ide.analysis.k2.integration

import ru.lazyhat.compukters.compiler.project.ProjectSnapshot
import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.analysis.AnalysisBundleIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.SnapshotPresentationAcceptance
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.controller.AdmittedAnalysisSnapshot
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisClientResult
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisWorkerController
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisWorkerPolicy
import ru.lazyhat.compukters.ide.analysis.controller.SnapshotOpenResult
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisBundle
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisProfile
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFailureKind
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisWorkerIdentity
import ru.lazyhat.compukters.worker.payload.ToolingBundleLoader
import ru.lazyhat.compukters.worker.process.JdkWorkerProcessFactory
import ru.lazyhat.compukters.worker.process.WorkerLaunch
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SnapshotAdmissionTest {
    @Test
    fun `forked worker admits multi-file snapshot replaces profile and closes it`() {
        withController { controller ->
            val first = snapshot(profileByte = 1)
            assertEquals(SnapshotOpenResult.Opened(first.identity), controller.open(first).get(90, TimeUnit.SECONDS))

            val replacement = snapshot(profileByte = 2)
            assertEquals(
                SnapshotOpenResult.Opened(replacement.identity),
                controller.open(replacement).get(90, TimeUnit.SECONDS),
            )
            controller.closeSnapshot(replacement.identity).get(90, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `host snapshot boundary rejects malformed UTF-8 before process admission`() {
        assertFailsWith<IllegalArgumentException> {
            snapshot(profileByte = 1, main = byteArrayOf(0xC3.toByte(), 0x28))
        }
    }

    @Test
    fun `forked worker rejects a bundle whose content does not match its hash`() {
        val bundle = Files.createTempFile("compukters-mismatched-bundle-", ".jar")
        Files.write(bundle, byteArrayOf(1, 2, 3))
        try {
            val admitted =
                AdmittedAnalysisBundle(
                    AnalysisBundleIdentity("mismatched", Hash256.of(ByteArray(32) { 9 })),
                    bundle.toAbsolutePath().normalize().toString(),
                )
            withController { controller ->
                val failure =
                    assertIs<SnapshotOpenResult.Failure>(
                        controller.open(snapshot(profileByte = 1, bundles = listOf(admitted))).get(90, TimeUnit.SECONDS),
                    )
                assertEquals(AnalysisFailureKind.InvalidSnapshot, failure.kind)
            }
        } finally {
            Files.deleteIfExists(bundle)
        }
    }

    @Test
    fun `forked worker rejects a missing bundle`() {
        val missing =
            createTempDirectory("compukters-missing-bundle-")
                .resolve("missing.jar")
                .toAbsolutePath()
                .normalize()
        val admitted =
            AdmittedAnalysisBundle(
                AnalysisBundleIdentity("missing", Hash256.of(ByteArray(32) { 7 })),
                missing.toString(),
            )
        try {
            withController { controller ->
                val failure =
                    assertIs<SnapshotOpenResult.Failure>(
                        controller.open(snapshot(profileByte = 1, bundles = listOf(admitted))).get(90, TimeUnit.SECONDS),
                    )
                assertEquals(AnalysisFailureKind.InvalidSnapshot, failure.kind)
            }
        } finally {
            missing.parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `source path traversal is rejected before worker admission`() {
        assertFailsWith<IllegalArgumentException> { VirtualSourcePath.kotlin("../Main.kt") }
        assertFailsWith<IllegalArgumentException> { VirtualSourcePath.kotlin("demo/../../Main.kt") }
    }

    @Test
    fun `closed snapshot can be reopened after a worker restart`() {
        val admitted = snapshot(profileByte = 4)
        repeat(2) {
            withController { controller ->
                assertEquals(
                    SnapshotOpenResult.Opened(admitted.identity),
                    controller.open(admitted).get(90, TimeUnit.SECONDS),
                )
                controller.closeSnapshot(admitted.identity).get(90, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `forked worker returns real presentation and expression results`() {
        val main = "package demo\nval emoji = \"😀\"\nval broken: String = 42\nval inferred = \"ok\""
        val admitted = snapshot(profileByte = 5, main = main.encodeToByteArray())
        withController { controller ->
            assertEquals(SnapshotOpenResult.Opened(admitted.identity), controller.open(admitted).get(90, TimeUnit.SECONDS))

            val presentation =
                assertIs<AnalysisClientResult.Success>(
                    controller.query(AnalysisQuery.Presentation(admitted.identity)).get(90, TimeUnit.SECONDS),
                ).result as AnalysisResult.Presentation
            val active = assertIs<SnapshotPresentationAcceptance.Active>(presentation.value.accept(admitted.identity))
            assertTrue(active.diagnostics.any { it.path?.value == "demo/Main.kt" })

            val offset = main.lastIndexOf("\"ok\"") + 1
            val expression =
                assertIs<AnalysisClientResult.Success>(
                    controller
                        .query(
                            AnalysisQuery.ExpressionInfo(
                                admitted.identity,
                                VirtualSourcePath.kotlin("demo/Main.kt"),
                                offset,
                            ),
                        ).get(90, TimeUnit.SECONDS),
                ).result as AnalysisResult.ExpressionInfo
            assertEquals("kotlin.String", expression.value?.renderedType)
        }
    }

    private fun withController(block: (AnalysisWorkerController) -> Unit) {
        val payload = ToolingBundleLoader.load(Path.of(checkNotNull(System.getProperty("compukters.analysis.payload")))).profile("analysis")
        val temporaryRoot = createTempDirectory("compukters-analysis-integration-").toAbsolutePath().normalize()
        val limits = AnalysisLimits()
        val identity =
            AnalysisWorkerIdentity(
                payload.manifest.identityProperties.getValue("compiler"),
                payload.manifest.identityProperties.getValue("language"),
                Hash256.of(payload.manifest.payloadHash.toByteArray()),
            )
        val controller =
            AnalysisWorkerController(
                WorkerLaunch(
                    Path.of(checkNotNull(System.getProperty("compukters.analysis.java"))).toAbsolutePath().normalize(),
                    payload.classpath,
                    payload.manifest.mainClass,
                    512,
                    256,
                    temporaryRoot.resolve("worker"),
                    limits.frameBytes + 12,
                    64 * 1024,
                ),
                identity,
                limits,
                JdkWorkerProcessFactory(),
                AnalysisWorkerPolicy(30_000_000_000, 60_000_000_000, 250),
            )
        try {
            block(controller)
        } finally {
            controller.close()
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    private fun snapshot(
        profileByte: Int,
        main: ByteArray = "package demo\nfun answer() = helper()".encodeToByteArray(),
        bundles: List<AdmittedAnalysisBundle> = emptyList(),
    ): AdmittedAnalysisSnapshot {
        val sources =
            ProjectSnapshot.of(
                listOf(
                    ProjectSource(
                        VirtualSourcePath.kotlin("demo/Helper.kt"),
                        BinaryValue.of("package demo\nfun helper() = 42".encodeToByteArray()),
                    ),
                    ProjectSource(VirtualSourcePath.kotlin("demo/Main.kt"), BinaryValue.of(main)),
                ),
                WorkerLimits(),
            )
        val profile = AnalysisProfileIdentity(Hash256.of(ByteArray(32) { profileByte.toByte() }))
        val identity = AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(sources), profile)
        return AdmittedAnalysisSnapshot(identity, sources, AdmittedAnalysisProfile(profile, bundles), AnalysisLimits())
    }
}
