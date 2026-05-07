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

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

class RuntimeVmProfilingReportTest {
    @Test
    fun generatesRuntimeVmComparisonReport() {
        val nativeLibrary = System.getProperty(NATIVE_LIBRARY_PROPERTY)?.takeIf { it.isNotBlank() }
            ?: error("Runtime VM profiling comparison requires -D$NATIVE_LIBRARY_PROPERTY=/absolute/path/to/libckl_vm.so")
        val reportPath = Path.of(System.getProperty(REPORT_PATH_PROPERTY, DEFAULT_REPORT_PATH))

        val jvmProfile = RuntimeProfilingWorkload.withVmRunner("kotlin") { profileRunner("JVM") }
        val rustProfile = RuntimeProfilingWorkload.withVmRunner("rust", nativeLibrary) { profileRunner("Rust") }
        val markdown = RuntimeVmProfilingReportFormatter.markdown(jvmProfile, rustProfile)

        Files.createDirectories(reportPath.parent)
        reportPath.writeText(markdown)
        println("Runtime VM profiling comparison report: ${reportPath.absolutePathString()}")

        assertTrue(Files.exists(reportPath), "Expected report at ${reportPath.absolutePathString()}")
        assertTrue(markdown.contains("JVM"), markdown)
        assertTrue(markdown.contains("Rust"), markdown)
    }

    private fun profileRunner(runnerName: String): VmRunnerProfile =
        VmRunnerProfile(
            runnerName = runnerName,
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
                    heldEnterWorkload(),
                ),
        )

    private fun terminalWorkload(
        name: String,
        delayMillis: Long,
        bootTicks: Int,
        inputTicks: Int,
        enterTicks: Int,
    ): RuntimeWorkloadProfile {
        val run =
            RuntimeProfilingWorkload.runTerminalWorkload(
                delayMillis = delayMillis,
                bootTicks = bootTicks,
                inputTicks = inputTicks,
                enterTicks = enterTicks,
            )
        return RuntimeWorkloadProfile(
            name = name,
            display = run.displayMetrics.snapshot(),
            runtime = run.runtimeMetrics.snapshot(),
            compiler = run.compilerMetrics.snapshot(),
        )
    }

    private fun heldEnterWorkload(): RuntimeWorkloadProfile {
        val run = RuntimeProfilingWorkload.runHeldEnterWorkload(repeatEnterEvents = 120, settleTicks = 220)
        return RuntimeWorkloadProfile(
            name = "held Enter backlog",
            display = run.profiling.displayMetrics.snapshot(),
            runtime = run.profiling.runtimeMetrics.snapshot(),
            compiler = run.profiling.compilerMetrics.snapshot(),
            heldEnter = run.summaryMetrics,
        )
    }

    private companion object {
        const val NATIVE_LIBRARY_PROPERTY = "ckl.vm.native.library"
        const val REPORT_PATH_PROPERTY = "ckl.profiling.report.path"
        const val DEFAULT_REPORT_PATH = "build/reports/profiling/runtime-vm-comparison.md"
    }
}
