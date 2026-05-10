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
import ru.lazyhat.compukterkraft.core.Config
import ru.lazyhat.compukterkraft.core.gui.TerminalFontConstants
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class RuntimeVmProfilingReportTest {
    @Test
    fun nativeDaemonSmokeWorkloadRecordsDaemonMetrics() {
        assertNativeDaemonSmokeRun(RuntimeProfilingWorkload.runNativeDaemonSmokeWorkload(ticks = 20))
    }

    @Test
    fun writesCurrentRuntimeProfile() {
        val profilePathValue = System.getProperty(PROFILE_PATH_PROPERTY)
        assumeTrue(!profilePathValue.isNullOrBlank(), "Report profile path is only provided by profiling Gradle tasks")
        val profilePath = Path.of(profilePathValue)
        val runtimeName = System.getProperty(RUNTIME_NAME_PROPERTY, "Rust image")

        warmUpRuntime()
        val profile = profileRuntime(runtimeName)
        assertTrue(
            profile.workloads.any { it.name == "bundled terminal" },
            "expected runtime profile to include bundled terminal workload",
        )
        assertTrue(
            profile.workloads.any { it.name == "default-size terminal" },
            "expected runtime profile to include default-size terminal workload",
        )
        val runsDirValue = System.getProperty(RUNS_DIR_PROPERTY)
        if (runsDirValue.isNullOrBlank()) {
            RuntimeVmProfileCodec.write(profile, profilePath)
            println("Runtime VM $runtimeName profiling data: ${profilePath.absolutePathString()}")
        } else {
            val run =
                RuntimeVmProfilingReportArchive.writeRun(
                    profile = profile,
                    stableProfilePath = profilePath,
                    runsDir = Path.of(runsDirValue),
                    metadata =
                        RuntimeVmProfileRunMetadata(
                            timestamp =
                                System.getProperty(RUN_TIMESTAMP_PROPERTY)
                                    ?: RuntimeVmProfilingReportArchive.currentTimestamp(),
                            runtimeName = runtimeName,
                            gitCommit = System.getProperty(GIT_COMMIT_PROPERTY),
                        ),
                )
            println("Runtime VM $runtimeName profiling data: ${profilePath.absolutePathString()}")
            println("Runtime VM $runtimeName profiling run: ${Path.of(runsDirValue).resolve(run.metadata.timestamp).absolutePathString()}")
            val markdown =
                Path
                    .of(runsDirValue)
                    .resolve(run.metadata.timestamp)
                    .resolve(RuntimeVmProfilingReportArchive.MARKDOWN_FILE_NAME)
                    .readText()
            assertContains(markdown, "Native wait signals")
            assertContains(markdown, "Native process wait signals")
            assertContains(markdown, "Native fast-path calls")
        }

        assertTrue(profile.workloads.isNotEmpty())
    }

    private fun warmUpRuntime() {
        RuntimeProfilingWorkload.runTerminalWorkload(
            delayMillis = 0,
            bootTicks = 40,
            inputTicks = 10,
            enterTicks = 20,
        )
    }

    private fun profileRuntime(runtimeName: String): RuntimeVmProfile =
        RuntimeVmProfile(
            runtimeName = runtimeName,
            workloads =
                listOf(
                    terminalWorkload(
                        name = "sustained terminal no-delay",
                        delayMillis = 0,
                        bootTicks = 120,
                        inputTicks = 40,
                        enterTicks = 80,
                    ),
                    terminalWorkload(
                        name = "bundled terminal",
                        delayMillis = 10,
                        bootTicks = 80,
                        inputTicks = 20,
                        enterTicks = 40,
                    ),
                    terminalWorkload(
                        name = "default-size terminal",
                        delayMillis = 10,
                        bootTicks = 80,
                        inputTicks = 20,
                        enterTicks = 40,
                        displayWidth = Config.DEFAULT_COMPUTER_TERM_WIDTH * TerminalFontConstants.FONT_WIDTH,
                        displayHeight = Config.DEFAULT_COMPUTER_TERM_HEIGHT * TerminalFontConstants.FONT_HEIGHT,
                    ),
                    heldEnterWorkload(),
                    enterAutoscrollWorkload(),
                    nativeDaemonSmokeWorkload(),
                ),
        )

    private fun terminalWorkload(
        name: String,
        delayMillis: Long,
        bootTicks: Int,
        inputTicks: Int,
        enterTicks: Int,
        displayWidth: Int = 96,
        displayHeight: Int = 48,
    ): RuntimeWorkloadProfile {
        val run =
            RuntimeProfilingWorkload.runTerminalWorkload(
                delayMillis = delayMillis,
                bootTicks = bootTicks,
                inputTicks = inputTicks,
                enterTicks = enterTicks,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
            )
        return RuntimeWorkloadProfile(
            name = name,
            display = run.displayMetrics.snapshot(),
            client = run.clientMetrics.snapshot(),
            runtime = run.runtimeMetrics.snapshot(),
            compiler = run.compilerMetrics.snapshot(),
            pipeline = run.pipeline,
        )
    }

    private fun heldEnterWorkload(): RuntimeWorkloadProfile {
        val run = RuntimeProfilingWorkload.runHeldEnterWorkload(repeatEnterEvents = 120, settleTicks = 220)
        return RuntimeWorkloadProfile(
            name = "held Enter backlog",
            display = run.profiling.displayMetrics.snapshot(),
            client = run.profiling.clientMetrics.snapshot(),
            runtime = run.profiling.runtimeMetrics.snapshot(),
            compiler = run.profiling.compilerMetrics.snapshot(),
            heldEnter = run.summaryMetrics,
        )
    }

    private fun enterAutoscrollWorkload(): RuntimeWorkloadProfile {
        val run =
            RuntimeProfilingWorkload.runEnterAutoscrollWorkload(
                maxEnterEvents = 80,
                ticksPerEnter = 8,
                settleTicks = 80,
            )
        return RuntimeWorkloadProfile(
            name = "held Enter to autoscroll",
            display = run.profiling.displayMetrics.snapshot(),
            client = run.profiling.clientMetrics.snapshot(),
            runtime = run.profiling.runtimeMetrics.snapshot(),
            compiler = run.profiling.compilerMetrics.snapshot(),
            enterAutoscroll = run.summaryMetrics,
        )
    }

    private fun nativeDaemonSmokeWorkload(): RuntimeWorkloadProfile {
        val run = RuntimeProfilingWorkload.runNativeDaemonSmokeWorkload(ticks = 20)
        assertNativeDaemonSmokeRun(run)
        return RuntimeWorkloadProfile(
            name = "native daemon smoke",
            display = run.displayMetrics.snapshot(),
            client = run.clientMetrics.snapshot(),
            runtime = run.runtimeMetrics.snapshot(),
            compiler = run.compilerMetrics.snapshot(),
        )
    }

    private fun assertNativeDaemonSmokeRun(run: RuntimeProfilingWorkload.ProfilingRun) {
        val vm = run.runtimeMetrics.snapshot().vm
        assertTrue(vm.nativeDaemonTicks > 0, "expected native daemon ticks in smoke workload")
        assertTrue(vm.nativeDaemonTurns > 0, "expected native daemon turns in smoke workload")
    }

    private companion object {
        const val PROFILE_PATH_PROPERTY = "ckl.profiling.profile.path"
        const val RUNS_DIR_PROPERTY = "ckl.profiling.runs.dir"
        const val RUN_TIMESTAMP_PROPERTY = "ckl.profiling.run.timestamp"
        const val GIT_COMMIT_PROPERTY = "ckl.profiling.git.commit"
        const val RUNTIME_NAME_PROPERTY = "ckl.profiling.runtime.name"
    }
}
