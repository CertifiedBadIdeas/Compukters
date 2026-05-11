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

import java.nio.file.Path

internal object ComputeVmBenchmarkRunner {
    fun run(
        libraryPath: String,
        rustCrateDir: Path,
        pythonCommand: String,
        pythonScriptPath: Path,
        iterations: Int,
        warmupIterations: Int,
        samples: Int,
    ): ComputeVmBenchmarkReport {
        require(iterations > 0) { "Benchmark iterations must be positive." }
        require(warmupIterations >= 0) { "Benchmark warmup iterations must be non-negative." }
        require(samples > 0) { "Benchmark samples must be positive." }

        val ckVmRunner = CkVmComputeBenchmarkRunner(libraryPath)
        val kotlinJvmRunner = KotlinJvmComputeBenchmarkRunner
        val pythonRunner = PythonComputeBenchmarkRunner(pythonCommand, pythonScriptPath)
        val rustNativeRunner = RustNativeComputeBenchmarkRunner(rustCrateDir)

        return ComputeVmBenchmarkReport(
            samples = samples,
            workloads =
                ComputeVmBenchmarkWorkloads.all.map { workload ->
                    runWorkload(
                        workload = workload,
                        ckVmRunner = ckVmRunner,
                        kotlinJvmRunner = kotlinJvmRunner,
                        pythonRunner = pythonRunner,
                        rustNativeRunner = rustNativeRunner,
                        iterations = workload.scaleIterations(iterations),
                        warmupIterations = if (warmupIterations == 0) 0 else workload.scaleIterations(warmupIterations),
                        samples = samples,
                    )
                },
        )
    }

    private fun runWorkload(
        workload: ComputeVmBenchmarkWorkloadSpec,
        ckVmRunner: ComputeVmBenchmarkWorkloadRunner,
        kotlinJvmRunner: ComputeVmBenchmarkWorkloadRunner,
        pythonRunner: ComputeVmBenchmarkWorkloadRunner,
        rustNativeRunner: ComputeVmBenchmarkWorkloadRunner,
        iterations: Int,
        warmupIterations: Int,
        samples: Int,
    ): ComputeVmBenchmarkWorkloadReport {
        val runners = listOf(ckVmRunner, kotlinJvmRunner, pythonRunner, rustNativeRunner)
        runners.forEach { runner -> runner.warmUp(workload, warmupIterations) }

        val ckVm = ckVmRunner.run(workload, iterations, samples)
        val kotlinJvm = kotlinJvmRunner.run(workload, iterations, samples)
        check(kotlinJvm.checksum == ckVm.checksum) {
            "${kotlinJvmRunner.name} ${workload.name} checksum ${kotlinJvm.checksum} does not match CK VM checksum ${ckVm.checksum}"
        }

        val python = pythonRunner.run(workload, iterations, samples)
        check(python.checksum == ckVm.checksum) {
            "${pythonRunner.name} ${workload.name} checksum ${python.checksum} does not match CK VM checksum ${ckVm.checksum}"
        }

        val rustNative = rustNativeRunner.run(workload, iterations, samples)
        check(rustNative.checksum == ckVm.checksum) {
            "${rustNativeRunner.name} ${workload.name} checksum ${rustNative.checksum} does not match CK VM checksum ${ckVm.checksum}"
        }

        return ComputeVmBenchmarkWorkloadReport(
            workloadName = workload.name,
            iterations = iterations,
            checksum = ckVm.checksum,
            ckVmBestNanos = ckVm.bestNanos,
            kotlinJvmBestNanos = kotlinJvm.bestNanos,
            pythonBestNanos = python.bestNanos,
            rustNativeBestNanos = rustNative.bestNanos,
            ckVmMetrics = ckVm.ckVmMetrics,
        )
    }
}
