/*
 * The Compukter Kraft Developers
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

package ru.lazyhat.compukterkraft.impl.computer.vm

import org.junit.jupiter.api.Assumptions.assumeTrue
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeProfilingSnapshot
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeTickMetrics
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeVmMetrics
import ru.lazyhat.compukterkraft.core.device.vm.display.DisplayFrameBuildTotals
import ru.lazyhat.compukterkraft.core.device.vm.display.DisplayFrameMetrics
import ru.lazyhat.compukterkraft.core.device.vm.display.DisplayOperationMetrics
import ru.lazyhat.compukterkraft.core.device.vm.display.DisplayProfilingSnapshot
import ru.lazyhat.compukterkraft.lang.frontend.CompilerProfilingSnapshot
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeVmProfilingReportAggregationTest {
    @Test
    fun writesConfiguredHistoricalReport() {
        val runsDirValue = System.getProperty(RUNS_DIR_PROPERTY)
        val reportPathValue = System.getProperty(COMPARISON_PATH_PROPERTY)
        assumeTrue(!runsDirValue.isNullOrBlank(), "$RUNS_DIR_PROPERTY is only provided by profiling Gradle tasks")
        assumeTrue(!reportPathValue.isNullOrBlank(), "$COMPARISON_PATH_PROPERTY is only provided by profiling Gradle tasks")
        val reportPath = java.nio.file.Path.of(reportPathValue)

        val runs = RuntimeVmProfilingReportArchive.writeHistoricalReport(java.nio.file.Path.of(runsDirValue), reportPath)

        assertTrue(runs.isNotEmpty(), "Expected archived profiling runs under $runsDirValue")
        assertTrue(Files.exists(reportPath), "Expected historical report at $reportPath")
    }

    @Test
    fun readsEveryArchivedRunAndWritesHistoricalMarkdown() {
        val runsDir = createTempDirectory("runtime-vm-profile-runs")
        val reportPath = runsDir.resolveSibling("runtime-vm-comparison.md")
        writeRun(runsDir, "2026-05-08T14-00-00+03-00", runtimeNanos = 100)
        writeRun(runsDir, "2026-05-08T14-05-00+03-00", runtimeNanos = 50)

        RuntimeVmProfilingReportArchive.writeHistoricalReport(runsDir, reportPath)

        val markdown = reportPath.readText()
        assertTrue(markdown.contains("2026-05-08T14-00-00+03-00"), markdown)
        assertTrue(markdown.contains("2026-05-08T14-05-00+03-00"), markdown)
        assertTrue(markdown.contains("| Runtime all ticks | 50 ns | 0.50x |"), markdown)
    }

    @Test
    fun archivesStableProfileIntoTimestampedRunDirectory() {
        val root = createTempDirectory("runtime-vm-profile-archive")
        val stablePath = root.resolve("runtime-vm-image.tsv")
        val runsDir = root.resolve("runs")
        val profile = profile(runtimeNanos = 100)

        val run =
            RuntimeVmProfilingReportArchive.writeRun(
                profile = profile,
                stableProfilePath = stablePath,
                runsDir = runsDir,
                metadata =
                    RuntimeVmProfileRunMetadata(
                        timestamp = "2026-05-08T14-00-00+03-00",
                        runtimeName = "Rust image",
                        gitCommit = "abc1234",
                    ),
            )

        assertEquals("2026-05-08T14-00-00+03-00", run.metadata.timestamp)
        assertTrue(Files.exists(stablePath), "Expected stable profile at $stablePath")
        assertTrue(Files.exists(runsDir.resolve("2026-05-08T14-00-00+03-00").resolve("runtime-vm-image.tsv")))
        assertTrue(Files.exists(runsDir.resolve("2026-05-08T14-00-00+03-00").resolve("runtime-vm-image.md")))
    }

    private fun writeRun(
        runsDir: java.nio.file.Path,
        timestamp: String,
        runtimeNanos: Long,
    ) {
        val runDir = runsDir.resolve(timestamp)
        runDir.createDirectories()
        RuntimeVmProfileCodec.write(profile(runtimeNanos), runDir.resolve("runtime-vm-image.tsv"))
    }

    private fun profile(runtimeNanos: Long): RuntimeVmProfile =
        RuntimeVmProfile(
            runtimeName = "Rust image",
            workloads =
                listOf(
                    RuntimeWorkloadProfile(
                        name = "bundled terminal",
                        display =
                            DisplayProfilingSnapshot(
                                operations = DisplayOperationMetrics(presentCalls = 1, presentFrames = 1, presentNanos = 2),
                                frames = DisplayFrameMetrics(frameCount = 1, tileCount = 2, payloadBytes = 128),
                                frameBuild = DisplayFrameBuildTotals(buildCalls = 1, totalNanos = 3, tileCount = 2, payloadBytes = 128),
                            ),
                        runtime =
                            RuntimeProfilingSnapshot(
                                tick = RuntimeTickMetrics(serverTickCalls = 1, serverTickNanos = runtimeNanos),
                                vm = RuntimeVmMetrics(executionWindows = 1, executionWindowNanos = 4, hostCallSignals = 5),
                            ),
                        compiler = CompilerProfilingSnapshot(compileCalls = 1, compileNanos = 6, compiledSources = 1),
                    ),
                ),
        )

    private companion object {
        const val RUNS_DIR_PROPERTY = "ckl.profiling.runs.dir"
        const val COMPARISON_PATH_PROPERTY = "ckl.profiling.comparison.path"
    }
}
