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

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

internal data class ComputeVmBenchmarkReport(
    val samples: Int,
    val workloads: List<ComputeVmBenchmarkWorkloadReport>,
) {
    fun toTsv(): String =
        buildString {
            appendLine(
                "workload\titerations\tchecksum\tsamples\tlow_vm_best_ns\tkotlin_jvm_best_ns\tpython_best_ns\trust_native_best_ns\tlow_vm_iters_per_sec\tkotlin_jvm_iters_per_sec\tpython_iters_per_sec\trust_native_iters_per_sec\tlow_vm_vs_kotlin_slowdown\tlow_vm_vs_python_slowdown\tlow_vm_vs_rust_slowdown",
            )
            workloads.forEach { workload ->
                appendLine(
                    listOf(
                        workload.workloadName,
                        workload.iterations,
                        workload.checksum,
                        samples,
                        workload.lowVmBestNanos,
                        workload.kotlinJvmBestNanos,
                        workload.pythonBestNanos,
                        workload.rustNativeBestNanos,
                        formatPlain(workload.lowVmIterationsPerSecond),
                        formatPlain(workload.kotlinJvmIterationsPerSecond),
                        formatPlain(workload.pythonIterationsPerSecond),
                        formatPlain(workload.rustNativeIterationsPerSecond),
                        formatPlain(workload.lowVmVsKotlinSlowdown),
                        formatPlain(workload.lowVmVsPythonSlowdown),
                        formatPlain(workload.lowVmVsRustSlowdown),
                    ).joinToString("\t"),
                )
            }
        }

    fun toMarkdown(): String =
        buildString {
            appendLine("# CKL Compute VM Benchmark")
            appendLine()
            appendLine(
                "CPU-only workloads. The CKL low-level CKIM v4 VM is the measured VM baseline; Kotlin/JVM, Python, and optimized native Rust are comparison baselines.",
            )
            appendLine()
            appendLine(
                "| Workload | Iterations | Checksum | Low-level VM iter/s | Kotlin/JVM iter/s | Python iter/s | Rust native iter/s | Low VM vs Kotlin | Low VM vs Python | Low VM vs Rust |",
            )
            appendLine("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
            workloads.forEach { workload ->
                appendLine(
                    "| ${workload.workloadName} | ${formatInteger(workload.iterations)} | ${workload.checksum} | " +
                        "${formatGrouped(workload.lowVmIterationsPerSecond)} | " +
                        "${formatGrouped(workload.kotlinJvmIterationsPerSecond)} | " +
                        "${formatGrouped(workload.pythonIterationsPerSecond)} | ${formatGrouped(workload.rustNativeIterationsPerSecond)} | " +
                        "${formatPlain(workload.lowVmVsKotlinSlowdown)}x | ${formatPlain(workload.lowVmVsPythonSlowdown)}x | " +
                        "${formatPlain(workload.lowVmVsRustSlowdown)}x |",
                )
            }
            appendLine()
            appendLine("Best of $samples samples. Higher iter/s is better; lower slowdown is better.")
        }

    private companion object {
        fun formatPlain(value: Double): String = "%.3f".format(java.util.Locale.US, value)

        fun formatGrouped(value: Double): String = "%,.3f".format(java.util.Locale.US, value)

        fun formatInteger(value: Int): String = "%,d".format(java.util.Locale.US, value)

        fun formatInteger(value: Long): String = "%,d".format(java.util.Locale.US, value)
    }
}

internal data class ComputeVmBenchmarkWorkloadReport(
    val workloadName: String,
    val iterations: Int,
    val checksum: Int,
    val lowVmBestNanos: Long,
    val kotlinJvmBestNanos: Long,
    val pythonBestNanos: Long,
    val rustNativeBestNanos: Long,
) {
    val kotlinJvmIterationsPerSecond: Double
        get() = iterationsPerSecond(kotlinJvmBestNanos)

    val lowVmIterationsPerSecond: Double
        get() = iterationsPerSecond(lowVmBestNanos)

    val pythonIterationsPerSecond: Double
        get() = iterationsPerSecond(pythonBestNanos)

    val rustNativeIterationsPerSecond: Double
        get() = iterationsPerSecond(rustNativeBestNanos)

    val lowVmVsKotlinSlowdown: Double
        get() = lowVmBestNanos.toDouble() / kotlinJvmBestNanos.toDouble()

    val lowVmVsPythonSlowdown: Double
        get() = lowVmBestNanos.toDouble() / pythonBestNanos.toDouble()

    val lowVmVsRustSlowdown: Double
        get() = lowVmBestNanos.toDouble() / rustNativeBestNanos.toDouble()

    private fun iterationsPerSecond(nanos: Long): Double = iterations.toDouble() * NANOS_PER_SECOND / nanos.toDouble()

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}

internal object ComputeVmBenchmarkFiles {
    fun write(
        report: ComputeVmBenchmarkReport,
        tsvPath: Path,
        markdownPath: Path,
    ) {
        Files.createDirectories(tsvPath.parent)
        Files.createDirectories(markdownPath.parent)
        tsvPath.writeText(report.toTsv())
        markdownPath.writeText(report.toMarkdown())
    }
}
