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

package ru.lazyhat.compukterkraft.lang.runtime.blazing

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertTrue

class ComputeVmBenchmarkProfileTest {
    @Test
    fun writesComputeBenchmarkReport() {
        val tsvPathValue = System.getProperty(TSV_PATH_PROPERTY)
        val markdownPathValue = System.getProperty(MARKDOWN_PATH_PROPERTY)
        val libraryPath = System.getProperty(NATIVE_LIBRARY_PROPERTY)
        val nativeLibraryProfile = System.getProperty(NATIVE_LIBRARY_PROFILE_PROPERTY)
        val gitCommit = System.getProperty(GIT_COMMIT_PROPERTY, "unknown")
        val rustCrateDir = System.getProperty(RUST_CRATE_DIR_PROPERTY)
        val rustTargetProfile = System.getProperty(RUST_TARGET_PROFILE_PROPERTY)
        val pythonCommand = System.getProperty(PYTHON_COMMAND_PROPERTY, "python3")
        assumeTrue(!tsvPathValue.isNullOrBlank(), "Compute benchmark TSV path is only provided by profiling Gradle tasks")
        assumeTrue(!markdownPathValue.isNullOrBlank(), "Compute benchmark Markdown path is only provided by profiling Gradle tasks")
        assumeTrue(!libraryPath.isNullOrBlank(), "Compute benchmark requires the native Rux VM library")
        assumeTrue(!nativeLibraryProfile.isNullOrBlank(), "Compute benchmark requires the native library profile")
        assumeTrue(!rustCrateDir.isNullOrBlank(), "Compute benchmark requires the Rust VM crate directory")
        assumeTrue(!rustTargetProfile.isNullOrBlank(), "Compute benchmark requires the Rust target/profile label")
        val pythonScriptPath =
            Path.of(
                requireNotNull(javaClass.classLoader.getResource("compute_benchmark_baseline.py")) {
                    "Compute benchmark Python runner resource is missing"
                }.toURI(),
            )

        val report =
            ComputeVmBenchmarkRunner.run(
                libraryPath = libraryPath,
                nativeLibraryProfile = nativeLibraryProfile,
                gitCommit = gitCommit,
                rustCrateDir = Path.of(rustCrateDir),
                rustTargetProfile = rustTargetProfile,
                pythonCommand = pythonCommand,
                pythonScriptPath = pythonScriptPath,
                iterations = System.getProperty(ITERATIONS_PROPERTY, "500000").toInt(),
                warmupIterations = System.getProperty(WARMUP_ITERATIONS_PROPERTY, "50000").toInt(),
                samples = System.getProperty(SAMPLES_PROPERTY, "5").toInt(),
            )

        assertTrue(report.workloads.isNotEmpty(), "expected at least one compute benchmark workload")
        report.workloads.forEach { workload ->
            assertTrue(workload.lowVmBestNanos > 0, "expected ${workload.workloadName} low-level VM sample to be timed")
            assertTrue(workload.kotlinJvmBestNanos > 0, "expected ${workload.workloadName} Kotlin/JVM sample to be timed")
            assertTrue(workload.pythonBestNanos > 0, "expected ${workload.workloadName} Python sample to be timed")
            assertTrue(workload.rustNativeBestNanos > 0, "expected ${workload.workloadName} Rust native sample to be timed")
        }

        val tsvPath = Path.of(tsvPathValue)
        val markdownPath = Path.of(markdownPathValue)
        ComputeVmBenchmarkFiles.write(report, tsvPath, markdownPath)
        println("Compute VM benchmark TSV: ${tsvPath.absolutePathString()}")
        println("Compute VM benchmark report: ${markdownPath.absolutePathString()}")
    }

    private companion object {
        const val TSV_PATH_PROPERTY = "ckl.benchmark.compute.tsv.path"
        const val MARKDOWN_PATH_PROPERTY = "ckl.benchmark.compute.markdown.path"
        const val NATIVE_LIBRARY_PROPERTY = "rux.vm.native.library"
        const val NATIVE_LIBRARY_PROFILE_PROPERTY = "ckl.benchmark.native.library.profile"
        const val GIT_COMMIT_PROPERTY = "ckl.benchmark.git.commit"
        const val RUST_CRATE_DIR_PROPERTY = "ckl.benchmark.rust.crate.dir"
        const val RUST_TARGET_PROFILE_PROPERTY = "ckl.benchmark.rust.target.profile"
        const val PYTHON_COMMAND_PROPERTY = "ckl.benchmark.python.command"
        const val ITERATIONS_PROPERTY = "ckl.benchmark.iterations"
        const val WARMUP_ITERATIONS_PROPERTY = "ckl.benchmark.warmup.iterations"
        const val SAMPLES_PROPERTY = "ckl.benchmark.samples"
    }
}
