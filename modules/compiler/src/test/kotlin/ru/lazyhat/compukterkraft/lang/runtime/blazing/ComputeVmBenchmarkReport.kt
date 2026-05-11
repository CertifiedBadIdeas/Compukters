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
                "workload\titerations\tchecksum\tsamples\tck_vm_best_ns\tkotlin_jvm_best_ns\tpython_best_ns\trust_native_best_ns\tck_vm_iters_per_sec\tkotlin_jvm_iters_per_sec\tpython_iters_per_sec\trust_native_iters_per_sec\tck_vm_vs_kotlin_slowdown\tck_vm_vs_python_slowdown\tck_vm_vs_rust_slowdown",
            )
            workloads.forEach { workload ->
                appendLine(
                    listOf(
                        workload.workloadName,
                        workload.iterations,
                        workload.checksum,
                        samples,
                        workload.ckVmBestNanos,
                        workload.kotlinJvmBestNanos,
                        workload.pythonBestNanos,
                        workload.rustNativeBestNanos,
                        formatPlain(workload.ckVmIterationsPerSecond),
                        formatPlain(workload.kotlinJvmIterationsPerSecond),
                        formatPlain(workload.pythonIterationsPerSecond),
                        formatPlain(workload.rustNativeIterationsPerSecond),
                        formatPlain(workload.ckVmVsKotlinSlowdown),
                        formatPlain(workload.ckVmVsPythonSlowdown),
                        formatPlain(workload.ckVmVsRustSlowdown),
                    ).joinToString("\t"),
                )
            }
        }

    fun toMarkdown(): String =
        buildString {
            appendLine("# CKL Compute VM Benchmark")
            appendLine()
            appendLine(
                "CPU-only workloads. The CKL VM runs are timed through the native image VM, with the same work measured as Kotlin/JVM, Python, and optimized native Rust baselines.",
            )
            appendLine()
            appendLine(
                "| Workload | Iterations | Checksum | CK VM iter/s | Kotlin/JVM iter/s | Python iter/s | Rust native iter/s | CK VM vs Kotlin | CK VM vs Python | CK VM vs Rust |",
            )
            appendLine("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
            workloads.forEach { workload ->
                appendLine(
                    "| ${workload.workloadName} | ${formatInteger(workload.iterations)} | ${workload.checksum} | " +
                        "${formatGrouped(workload.ckVmIterationsPerSecond)} | ${formatGrouped(workload.kotlinJvmIterationsPerSecond)} | " +
                        "${formatGrouped(workload.pythonIterationsPerSecond)} | ${formatGrouped(workload.rustNativeIterationsPerSecond)} | " +
                        "${formatPlain(workload.ckVmVsKotlinSlowdown)}x | ${formatPlain(workload.ckVmVsPythonSlowdown)}x | " +
                        "${formatPlain(workload.ckVmVsRustSlowdown)}x |",
                )
            }
            appendLine()
            appendLine("Best of $samples samples. Higher iter/s is better; lower slowdown is better.")
        }

    private companion object {
        fun formatPlain(value: Double): String = "%.3f".format(java.util.Locale.US, value)

        fun formatGrouped(value: Double): String = "%,.3f".format(java.util.Locale.US, value)

        fun formatInteger(value: Int): String = "%,d".format(java.util.Locale.US, value)
    }
}

internal data class ComputeVmBenchmarkWorkloadReport(
    val workloadName: String,
    val iterations: Int,
    val checksum: Int,
    val ckVmBestNanos: Long,
    val kotlinJvmBestNanos: Long,
    val pythonBestNanos: Long,
    val rustNativeBestNanos: Long,
) {
    val ckVmIterationsPerSecond: Double
        get() = iterationsPerSecond(ckVmBestNanos)

    val kotlinJvmIterationsPerSecond: Double
        get() = iterationsPerSecond(kotlinJvmBestNanos)

    val pythonIterationsPerSecond: Double
        get() = iterationsPerSecond(pythonBestNanos)

    val rustNativeIterationsPerSecond: Double
        get() = iterationsPerSecond(rustNativeBestNanos)

    val ckVmVsKotlinSlowdown: Double
        get() = ckVmBestNanos.toDouble() / kotlinJvmBestNanos.toDouble()

    val ckVmVsPythonSlowdown: Double
        get() = ckVmBestNanos.toDouble() / pythonBestNanos.toDouble()

    val ckVmVsRustSlowdown: Double
        get() = ckVmBestNanos.toDouble() / rustNativeBestNanos.toDouble()

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
