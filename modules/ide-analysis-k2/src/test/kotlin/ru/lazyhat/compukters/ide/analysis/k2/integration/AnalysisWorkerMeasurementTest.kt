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
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.CompletionTrigger
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.controller.AdmittedAnalysisSnapshot
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisClientResult
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisServiceLifetime
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisWorkerController
import ru.lazyhat.compukters.ide.analysis.controller.AnalysisWorkerPolicy
import ru.lazyhat.compukters.ide.analysis.controller.SnapshotOpenResult
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisProfile
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisWorkerIdentity
import ru.lazyhat.compukters.worker.payload.WorkerPayloadLoader
import ru.lazyhat.compukters.worker.process.JdkWorkerProcessFactory
import ru.lazyhat.compukters.worker.process.WorkerLaunch
import ru.lazyhat.compukters.worker.process.WorkerProcess
import ru.lazyhat.compukters.worker.process.WorkerProcessFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AnalysisWorkerMeasurementTest {
    @Test
    fun `real worker emits bounded baseline measurements and exits after idle`() {
        val payload = WorkerPayloadLoader.load(Path.of(checkNotNull(System.getProperty("compukters.analysis.payload"))))
        val java = Path.of(checkNotNull(System.getProperty("compukters.analysis.java"))).toAbsolutePath().normalize()
        val root = createTempDirectory("compukters-analysis-measurement-").toAbsolutePath().normalize()
        val limits = AnalysisLimits()
        val launch =
            WorkerLaunch(
                java,
                payload.classpath,
                payload.manifest.mainClass,
                MAXIMUM_HEAP_MIB,
                MAXIMUM_METASPACE_MIB,
                root.resolve("worker"),
                limits.frameBytes + 12,
                64 * 1024,
            )
        val workerIdentity =
            AnalysisWorkerIdentity(
                payload.manifest.identityProperties.getValue("compiler"),
                payload.manifest.identityProperties.getValue("language"),
                Hash256.of(payload.manifest.payloadHash.toByteArray()),
            )
        val factory = MeasuringWorkerFactory()
        val service =
            AnalysisServiceLifetime(IDLE_NANOS) {
                AnalysisWorkerController(
                    launch,
                    workerIdentity,
                    limits,
                    factory,
                    AnalysisWorkerPolicy(30_000_000_000, 60_000_000_000, 250),
                )
            }
        try {
            val first = snapshot("fun candidate() = Unit\nfun main() { can }", 1, limits)
            val second = snapshot("fun candidate() = Unit\nfun main() { candidate() }", 2, limits)
            val session = service.openSession()
            val client = session.client
            val coldOpen = measured { assertIs<SnapshotOpenResult.Opened>(client.open(first).get(90, TimeUnit.SECONDS)) }
            val presentation =
                measured {
                    assertIs<AnalysisClientResult.Success>(
                        client.query(AnalysisQuery.Presentation(first.identity)).get(90, TimeUnit.SECONDS),
                    )
                }
            val completion =
                measured {
                    assertIs<AnalysisClientResult.Success>(
                        client
                            .query(
                                AnalysisQuery.Completion(
                                    first.identity,
                                    VirtualSourcePath.kotlin("main.kt"),
                                    first.sources.sources
                                        .single()
                                        .content
                                        .toByteArray()
                                        .decodeToString()
                                        .lastIndexOf("can") + 3,
                                    CompletionTrigger.Manual,
                                ),
                            ).get(90, TimeUnit.SECONDS),
                    )
                }
            val replacement = measured { assertIs<SnapshotOpenResult.Opened>(client.open(second).get(90, TimeUnit.SECONDS)) }
            val process = checkNotNull(factory.process)
            val pid = checkNotNull(process.processId)
            val residentBytes = residentBytes(pid)
            val memory = jcmdMemory(java, pid)

            assertTrue(memory.heapUsedBytes <= MAXIMUM_HEAP_MIB * MIB)
            assertTrue(memory.metaspaceUsedBytes <= MAXIMUM_METASPACE_MIB * MIB)
            assertTrue(residentBytes > 0)

            session.close()
            awaitExit(pid)
            val report =
                MeasurementReport(
                    coldOpen,
                    presentation,
                    completion,
                    replacement,
                    residentBytes,
                    memory.heapUsedBytes,
                    memory.metaspaceUsedBytes,
                    postIdleProcessExited = true,
                ).render()
            println(report)
            assertTrue(report.startsWith("compukters.analysis.measurement.v1\n"))
        } finally {
            service.close()
            root.toFile().deleteRecursively()
        }
    }

    private fun snapshot(
        text: String,
        profileByte: Int,
        limits: AnalysisLimits,
    ): AdmittedAnalysisSnapshot {
        val sources =
            ProjectSnapshot.of(
                listOf(ProjectSource(VirtualSourcePath.kotlin("main.kt"), BinaryValue.of(text.encodeToByteArray()))),
                WorkerLimits(),
            )
        val profile = AnalysisProfileIdentity(Hash256.of(ByteArray(32) { profileByte.toByte() }))
        val identity = AnalysisSnapshotIdentity(SourceSnapshotIdentity.of(sources), profile)
        return AdmittedAnalysisSnapshot(identity, sources, AdmittedAnalysisProfile(profile, emptyList()), limits)
    }

    private fun measured(action: () -> Unit): Long {
        val started = System.nanoTime()
        action()
        return System.nanoTime() - started
    }

    private fun residentBytes(pid: Long): Long {
        val line = Files.readAllLines(Path.of("/proc/$pid/status")).first { it.startsWith("VmRSS:") }
        return line
            .removePrefix("VmRSS:")
            .trim()
            .substringBefore(' ')
            .toLong() * 1024
    }

    private fun jcmdMemory(
        java: Path,
        pid: Long,
    ): WorkerMemory {
        val heapOutput = jcmd(java, pid, "GC.heap_info")
        val countersOutput = jcmd(java, pid, "PerfCounter.print")
        val heapKiB = requireNotNull(Regex("used (\\d+)K").find(heapOutput)) { heapOutput }.groupValues[1].toLong()
        val metaspaceBytes =
            requireNotNull(Regex("sun\\.gc\\.metaspace\\.used=(\\d+)").find(countersOutput)) { countersOutput }
                .groupValues[1]
                .toLong()
        return WorkerMemory(heapKiB * 1024, metaspaceBytes)
    }

    private fun jcmd(
        java: Path,
        pid: Long,
        command: String,
    ): String {
        val process =
            ProcessBuilder(java.parent.resolve("jcmd").toString(), pid.toString(), command)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { output }
        return output
    }

    private fun awaitExit(pid: Long) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "analysis worker $pid remained alive after idle")
    }

    private companion object {
        const val MAXIMUM_HEAP_MIB = 512
        const val MAXIMUM_METASPACE_MIB = 256
        const val MIB = 1024L * 1024L
        const val IDLE_NANOS = 100_000_000L
    }
}

private class MeasuringWorkerFactory : WorkerProcessFactory {
    private val delegate = JdkWorkerProcessFactory()

    @Volatile var process: WorkerProcess? = null
        private set

    override fun start(launch: WorkerLaunch): WorkerProcess = delegate.start(launch).also { process = it }
}

private data class WorkerMemory(
    val heapUsedBytes: Long,
    val metaspaceUsedBytes: Long,
)

private data class MeasurementReport(
    val coldStartupOpenNanos: Long,
    val warmPresentationNanos: Long,
    val warmCompletionNanos: Long,
    val snapshotReplacementNanos: Long,
    val idleResidentBytes: Long,
    val idleHeapUsedBytes: Long,
    val idleMetaspaceUsedBytes: Long,
    val postIdleProcessExited: Boolean,
) {
    fun render(): String =
        buildString {
            appendLine("compukters.analysis.measurement.v1")
            appendLine("coldStartupOpenNanos=$coldStartupOpenNanos")
            appendLine("warmPresentationNanos=$warmPresentationNanos")
            appendLine("warmCompletionNanos=$warmCompletionNanos")
            appendLine("snapshotReplacementNanos=$snapshotReplacementNanos")
            appendLine("idleResidentBytes=$idleResidentBytes")
            appendLine("idleHeapUsedBytes=$idleHeapUsedBytes")
            appendLine("idleMetaspaceUsedBytes=$idleMetaspaceUsedBytes")
            appendLine("postIdleProcessExited=$postIdleProcessExited")
        }
}
