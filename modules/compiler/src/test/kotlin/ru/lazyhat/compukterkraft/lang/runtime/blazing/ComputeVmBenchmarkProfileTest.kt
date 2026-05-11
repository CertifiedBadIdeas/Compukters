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
        val rustCrateDir = System.getProperty(RUST_CRATE_DIR_PROPERTY)
        assumeTrue(!tsvPathValue.isNullOrBlank(), "Compute benchmark TSV path is only provided by profiling Gradle tasks")
        assumeTrue(!markdownPathValue.isNullOrBlank(), "Compute benchmark Markdown path is only provided by profiling Gradle tasks")
        assumeTrue(!libraryPath.isNullOrBlank(), "Compute benchmark requires the native CKL VM library")
        assumeTrue(!rustCrateDir.isNullOrBlank(), "Compute benchmark requires the Rust VM crate directory")

        val report =
            ComputeVmBenchmarkRunner.run(
                libraryPath = libraryPath,
                rustCrateDir = Path.of(rustCrateDir),
                iterations = System.getProperty(ITERATIONS_PROPERTY, "500000").toInt(),
                warmupIterations = System.getProperty(WARMUP_ITERATIONS_PROPERTY, "50000").toInt(),
                samples = System.getProperty(SAMPLES_PROPERTY, "5").toInt(),
            )

        assertTrue(report.workloads.isNotEmpty(), "expected at least one compute benchmark workload")
        report.workloads.forEach { workload ->
            assertTrue(workload.ckVmBestNanos > 0, "expected ${workload.workloadName} CK VM sample to be timed")
            assertTrue(workload.kotlinJvmBestNanos > 0, "expected ${workload.workloadName} Kotlin/JVM sample to be timed")
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
        const val NATIVE_LIBRARY_PROPERTY = "ckl.vm.native.library"
        const val RUST_CRATE_DIR_PROPERTY = "ckl.benchmark.rust.crate.dir"
        const val ITERATIONS_PROPERTY = "ckl.benchmark.iterations"
        const val WARMUP_ITERATIONS_PROPERTY = "ckl.benchmark.warmup.iterations"
        const val SAMPLES_PROPERTY = "ckl.benchmark.samples"
    }
}
