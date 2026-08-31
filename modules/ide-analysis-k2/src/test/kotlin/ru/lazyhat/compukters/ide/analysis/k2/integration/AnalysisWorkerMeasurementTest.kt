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
import ru.lazyhat.compukters.ide.analysis.k2.measurement.AnalysisMeasurementFixture
import ru.lazyhat.compukters.ide.analysis.k2.measurement.AnalysisMeasurementFixtures
import ru.lazyhat.compukters.ide.analysis.k2.measurement.AnalysisPerformanceReport
import ru.lazyhat.compukters.ide.analysis.k2.measurement.PhaseSamples
import ru.lazyhat.compukters.ide.analysis.protocol.AdmittedAnalysisProfile
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFrameCodec
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisMessageType
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisWorkerIdentity
import ru.lazyhat.compukters.worker.payload.ToolingBundleLoader
import ru.lazyhat.compukters.worker.process.JdkWorkerProcessFactory
import ru.lazyhat.compukters.worker.process.WorkerLaunch
import ru.lazyhat.compukters.worker.process.WorkerProcess
import ru.lazyhat.compukters.worker.process.WorkerProcessFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AnalysisWorkerMeasurementTest {
    @Test
    fun `real worker emits bounded baseline measurements and exits after idle`() {
        val payload = ToolingBundleLoader.load(Path.of(checkNotNull(System.getProperty("compukters.analysis.payload")))).profile("analysis")
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
            val firstFixture = AnalysisMeasurementFixtures.singleFile()
            val secondFixture =
                AnalysisMeasurementFixture(
                    firstFixture.sources.map { source ->
                        source.copy(text = source.text.replace("fun completionProbe() { can }", "fun completionProbe() { candidate() }"))
                    },
                )
            val first = snapshot(firstFixture, 1, limits)
            val second = snapshot(secondFixture, 1, limits)
            val session = service.openSession()
            val client = session.client
            val coldOpen = measured { assertIs<SnapshotOpenResult.Opened>(client.open(first).get(90, TimeUnit.SECONDS)) }
            val presentation =
                measured {
                    assertIs<AnalysisClientResult.Success>(
                        client.query(first, AnalysisQuery.Presentation(first.identity)).get(90, TimeUnit.SECONDS),
                    )
                }
            val completion =
                measured {
                    assertIs<AnalysisClientResult.Success>(
                        client
                            .query(
                                first,
                                AnalysisQuery.Completion(
                                    first.identity,
                                    VirtualSourcePath.kotlin("benchmark/Main.kt"),
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
            factory.clearQueryWrites()
            val cancelledQuery = client.query(second, AnalysisQuery.Presentation(second.identity))
            factory.awaitQueryWrite()
            val cancellation =
                measured {
                    assertTrue(client.cancel(cancelledQuery))
                    assertIs<AnalysisClientResult.Cancelled>(cancelledQuery.get(90, TimeUnit.SECONDS))
                }
            val reopen = measured { assertIs<SnapshotOpenResult.Opened>(client.open(second).get(90, TimeUnit.SECONDS)) }
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
                AnalysisPerformanceReport(
                    snapshotApply = PhaseSamples(listOf(coldOpen, replacement, reopen)),
                    presentation = PhaseSamples(listOf(presentation)),
                    completion = PhaseSamples(listOf(completion)),
                    endToEndPresentation = PhaseSamples(listOf(Math.addExact(coldOpen, presentation))),
                    endToEndCompletion = PhaseSamples(listOf(Math.addExact(replacement, completion))),
                    cancellation = PhaseSamples(listOf(cancellation)),
                    workerStarts = factory.starts,
                    fullRebuilds = 3,
                    incrementalUpdates = 0,
                    heapBytes = memory.heapUsedBytes,
                    metaspaceBytes = memory.metaspaceUsedBytes,
                    rssBytes = residentBytes,
                ).render()
            println(report)
            assertTrue(report.startsWith("compukters.analysis.performance.v2\n"))
        } finally {
            service.close()
            root.toFile().deleteRecursively()
        }
    }

    private fun snapshot(
        fixture: AnalysisMeasurementFixture,
        profileByte: Int,
        limits: AnalysisLimits,
    ): AdmittedAnalysisSnapshot {
        val sources =
            ProjectSnapshot.of(
                fixture.sources.map { source -> ProjectSource(source.path, BinaryValue.of(source.text.encodeToByteArray())) },
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
    private val queryWrites = LinkedBlockingQueue<Unit>()

    @Volatile var process: WorkerProcess? = null
        private set
    var starts: Int = 0
        private set

    override fun start(launch: WorkerLaunch): WorkerProcess {
        starts += 1
        return MeasuringWorkerProcess(delegate.start(launch), queryWrites).also { process = it }
    }

    fun clearQueryWrites() = queryWrites.clear()

    fun awaitQueryWrite() {
        checkNotNull(queryWrites.poll(30, TimeUnit.SECONDS)) { "analysis query was not written" }
    }
}

private class MeasuringWorkerProcess(
    private val delegate: WorkerProcess,
    private val queryWrites: LinkedBlockingQueue<Unit>,
) : WorkerProcess by delegate {
    override fun writeFrame(frame: ByteArray) {
        if (AnalysisFrameCodec.decode(frame, frame.size).type == AnalysisMessageType.Query) queryWrites.offer(Unit)
        delegate.writeFrame(frame)
    }
}

private data class WorkerMemory(
    val heapUsedBytes: Long,
    val metaspaceUsedBytes: Long,
)
