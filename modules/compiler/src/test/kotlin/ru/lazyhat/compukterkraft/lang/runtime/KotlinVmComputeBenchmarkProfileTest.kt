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

package ru.lazyhat.compukterkraft.lang.runtime

import ru.lazyhat.compukterkraft.lang.api.BytecodeModule
import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.writeText
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals

class KotlinVmComputeBenchmarkProfileTest {
    @Test
    fun writesComputeBenchmarkReport() {
        val iterations = System.getProperty("ckl.benchmark.iterations", "10000").toInt().coerceAtLeast(1)
        val warmupIterations = System.getProperty("ckl.benchmark.warmup.iterations", "1000").toInt().coerceAtLeast(0)
        val samples = System.getProperty("ckl.benchmark.samples", "3").toInt().coerceAtLeast(1)
        val reportsDir = Path("build/reports/profiling")
        val report =
            KotlinVmComputeBenchmarkReport(
                samples = samples,
                workloads =
                    workloads.map { workload ->
                        val scaledIterations = workload.scaleIterations(iterations)
                        val scaledWarmup = if (warmupIterations == 0) 0 else workload.scaleIterations(warmupIterations)
                        if (scaledWarmup > 0) {
                            runKotlinVm(workload, scaledWarmup, samples = 1)
                            bestOf(samples = 1, workload = workload) { workload.runKotlinJvm(scaledWarmup) }
                        }
                        val vm = runKotlinVm(workload, scaledIterations, samples)
                        val kotlinJvm = bestOf(samples, workload) { workload.runKotlinJvm(scaledIterations) }
                        assertEquals(kotlinJvm.checksum, vm.checksum, "${workload.name} checksum mismatch")
                        KotlinVmComputeBenchmarkWorkloadReport(
                            workloadName = workload.name,
                            iterations = scaledIterations,
                            checksum = vm.checksum,
                            kotlinVmBestNanos = vm.bestNanos,
                            kotlinJvmBestNanos = kotlinJvm.bestNanos,
                        )
                    },
            )

        Files.createDirectories(reportsDir)
        val tsv = reportsDir.resolve("kotlin-vm-compute-benchmark.tsv")
        val markdown = reportsDir.resolve("kotlin-vm-compute-benchmark.md")
        tsv.writeText(report.toTsv())
        markdown.writeText(report.toMarkdown())
        println("Kotlin VM compute benchmark TSV: ${tsv.toAbsolutePath()}")
        println("Kotlin VM compute benchmark report: ${markdown.toAbsolutePath()}")
    }

    private fun runKotlinVm(
        workload: Workload,
        iterations: Int,
        samples: Int,
    ): BenchmarkRun {
        val module = compile(workload, iterations)
        return bestOf(samples, workload) { runModule(module) }
    }

    private fun compile(
        workload: Workload,
        iterations: Int,
    ): BytecodeModule {
        val artifact = LanguageFrontend().compile("${workload.name}.ck", workload.source(iterations))
        return artifact.module
            ?: error(
                "Kotlin VM compute benchmark ${workload.name} did not compile:\n" +
                    artifact.analysis.diagnostics.joinToString("\n"),
            )
    }

    private fun runModule(module: BytecodeModule): Int {
        val vm =
            BytecodeVirtualMachine(
                module = module,
                instructionBudgetPerSlice = Int.MAX_VALUE,
                maxVmRamBytes = Long.MAX_VALUE,
            )
        while (true) {
            when (val signal = vm.runUntilSignal()) {
                VmSignal.Halt -> {
                    return when (val result = vm.snapshot().lastResult) {
                        is VmValue.IntValue -> result.value
                        else -> error("Kotlin VM benchmark halted with non-Int value: $result")
                    }
                }

                VmSignal.Pause -> Unit
                else -> error("Kotlin VM benchmark unexpectedly yielded signal: $signal")
            }
        }
    }
}

private data class BenchmarkRun(
    val checksum: Int,
    val bestNanos: Long,
)

private data class Workload(
    val name: String,
    val scaleIterations: (Int) -> Int,
    val source: (Int) -> String,
    val runKotlinJvm: (Int) -> Int,
)

private data class KotlinVmComputeBenchmarkReport(
    val samples: Int,
    val workloads: List<KotlinVmComputeBenchmarkWorkloadReport>,
) {
    fun toTsv(): String =
        buildString {
            appendLine(
                "workload\titerations\tchecksum\tsamples\tkotlin_vm_best_ns\tkotlin_jvm_best_ns\tkotlin_vm_iters_per_sec\tkotlin_jvm_iters_per_sec\tkotlin_vm_vs_kotlin_slowdown",
            )
            workloads.forEach { workload ->
                appendLine(
                    listOf(
                        workload.workloadName,
                        workload.iterations,
                        workload.checksum,
                        samples,
                        workload.kotlinVmBestNanos,
                        workload.kotlinJvmBestNanos,
                        formatPlain(workload.kotlinVmIterationsPerSecond),
                        formatPlain(workload.kotlinJvmIterationsPerSecond),
                        formatPlain(workload.kotlinVmVsKotlinSlowdown),
                    ).joinToString("\t"),
                )
            }
        }

    fun toMarkdown(): String =
        buildString {
            appendLine("# CKL Kotlin VM Compute Benchmark")
            appendLine()
            appendLine("CPU-only workloads running through the legacy Kotlin bytecode VM.")
            appendLine()
            appendLine("| Workload | Iterations | Checksum | Kotlin VM iter/s | Kotlin/JVM iter/s | Kotlin VM vs Kotlin |")
            appendLine("| --- | ---: | ---: | ---: | ---: | ---: |")
            workloads.forEach { workload ->
                appendLine(
                    "| ${workload.workloadName} | ${formatInteger(workload.iterations)} | ${workload.checksum} | " +
                        "${formatGrouped(workload.kotlinVmIterationsPerSecond)} | " +
                        "${formatGrouped(workload.kotlinJvmIterationsPerSecond)} | " +
                        "${formatPlain(workload.kotlinVmVsKotlinSlowdown)}x |",
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

private data class KotlinVmComputeBenchmarkWorkloadReport(
    val workloadName: String,
    val iterations: Int,
    val checksum: Int,
    val kotlinVmBestNanos: Long,
    val kotlinJvmBestNanos: Long,
) {
    val kotlinVmIterationsPerSecond: Double get() = iterationsPerSecond(kotlinVmBestNanos)
    val kotlinJvmIterationsPerSecond: Double get() = iterationsPerSecond(kotlinJvmBestNanos)
    val kotlinVmVsKotlinSlowdown: Double get() = kotlinVmBestNanos.toDouble() / kotlinJvmBestNanos.toDouble()

    private fun iterationsPerSecond(nanos: Long): Double = iterations.toDouble() * 1_000_000_000.0 / nanos.toDouble()
}

private fun bestOf(
    samples: Int,
    workload: Workload,
    runSample: () -> Int,
): BenchmarkRun {
    var checksum = 0
    val bestNanos =
        List(samples.coerceAtLeast(1)) { sampleIndex ->
            var sampleChecksum = 0
            val elapsed =
                measureNanoTime {
                    sampleChecksum = runSample()
                }
            if (sampleIndex == 0) {
                checksum = sampleChecksum
            } else {
                check(checksum == sampleChecksum) {
                    "${workload.name} checksum changed between samples: $checksum != $sampleChecksum"
                }
            }
            elapsed
        }.min()
    return BenchmarkRun(checksum, bestNanos)
}

private val workloads: List<Workload> =
    listOf(
        Workload(
            name = "integer-mix",
            scaleIterations = { base -> base.coerceAtLeast(1) },
            source = ::integerMixSource,
            runKotlinJvm = ::runIntegerMix,
        ),
        Workload(
            name = "function-mix",
            scaleIterations = { base -> base.coerceAtLeast(1) },
            source = ::functionMixSource,
            runKotlinJvm = ::runFunctionMix,
        ),
        Workload(
            name = "branch-div",
            scaleIterations = { base -> (base / 4).coerceAtLeast(1) },
            source = ::branchDivSource,
            runKotlinJvm = ::runBranchDiv,
        ),
        Workload(
            name = "recursive-fib",
            scaleIterations = { base -> (base / 5_000).coerceAtLeast(20) },
            source = ::recursiveFibSource,
            runKotlinJvm = ::runRecursiveFib,
        ),
    )

private fun integerMixSource(iterations: Int): String =
    """
    fun integerMix(iterations: Int): Int {
        var state: Int = 305419896;
        var acc: Int = -1640531527;
        var i: Int = 0;
        while (i < iterations) {
            state = state * 1664525 + 1013904223;
            val x: Int = state ^ (state >> 16);
            acc = (acc + x) ^ (acc << 5);
            acc = acc + ((i * 31) ^ (x >> 3));
            i = i + 1;
        }
        return acc;
    }

    pub fun main(): Int {
        return integerMix($iterations);
    }
    """.trimIndent()

private fun functionMixSource(iterations: Int): String =
    """
    fun mixA(value: Int, index: Int): Int {
        return ((value + index * 17) ^ (value << 3)) + (index >> 1);
    }

    fun mixB(value: Int, index: Int): Int {
        return ((value ^ (index * 131)) + (value >> 2)) ^ (index << 4);
    }

    fun functionMix(iterations: Int): Int {
        var acc: Int = 324508639;
        var i: Int = 0;
        while (i < iterations) {
            acc = mixB(mixA(acc, i), i);
            i = i + 1;
        }
        return acc;
    }

    pub fun main(): Int {
        return functionMix($iterations);
    }
    """.trimIndent()

private fun branchDivSource(iterations: Int): String =
    """
    fun remainder(value: Int, divisor: Int): Int {
        return value - (value / divisor) * divisor;
    }

    fun branchDiv(iterations: Int): Int {
        var acc: Int = 7;
        var i: Int = 1;
        while (i < iterations + 1) {
            val mod: Int = remainder(i, 11);
            if (mod == 0) {
                acc = acc + i / 3;
            } else {
                if (mod < 5) {
                    acc = (acc ^ (i * 17)) + remainder(i, 7);
                } else {
                    acc = acc - (i / (mod + 1)) + (acc << 1);
                }
            }
            i = i + 1;
        }
        return acc;
    }

    pub fun main(): Int {
        return branchDiv($iterations);
    }
    """.trimIndent()

private fun recursiveFibSource(iterations: Int): String =
    """
    fun remainder(value: Int, divisor: Int): Int {
        return value - (value / divisor) * divisor;
    }

    fun fib(value: Int): Int {
        if (value < 2) {
            return value;
        }
        return fib(value - 1) + fib(value - 2);
    }

    fun recursiveFib(iterations: Int): Int {
        var acc: Int = 0;
        var i: Int = 0;
        while (i < iterations) {
            val n: Int = 10 + remainder(i, 6);
            acc = acc + (fib(n) ^ (i * 31));
            i = i + 1;
        }
        return acc;
    }

    pub fun main(): Int {
        return recursiveFib($iterations);
    }
    """.trimIndent()

private fun runIntegerMix(iterations: Int): Int {
    var state = 305_419_896
    var acc = -1_640_531_527
    var i = 0
    while (i < iterations) {
        state = state * 1_664_525 + 1_013_904_223
        val x = state xor (state shr 16)
        acc = (acc + x) xor (acc shl 5)
        acc += (i * 31) xor (x shr 3)
        i += 1
    }
    return acc
}

private fun runFunctionMix(iterations: Int): Int {
    var acc = 324_508_639
    var i = 0
    while (i < iterations) {
        acc = functionMixB(functionMixA(acc, i), i)
        i += 1
    }
    return acc
}

private fun functionMixA(
    value: Int,
    index: Int,
): Int = ((value + index * 17) xor (value shl 3)) + (index shr 1)

private fun functionMixB(
    value: Int,
    index: Int,
): Int = ((value xor (index * 131)) + (value shr 2)) xor (index shl 4)

private fun runBranchDiv(iterations: Int): Int {
    var acc = 7
    var i = 1
    while (i < iterations + 1) {
        val mod = i % 11
        acc =
            if (mod == 0) {
                acc + i / 3
            } else if (mod < 5) {
                (acc xor (i * 17)) + (i % 7)
            } else {
                acc - (i / (mod + 1)) + (acc shl 1)
            }
        i += 1
    }
    return acc
}

private fun runRecursiveFib(iterations: Int): Int {
    var acc = 0
    var i = 0
    while (i < iterations) {
        val n = 10 + (i % 6)
        acc += recursiveFib(n) xor (i * 31)
        i += 1
    }
    return acc
}

private fun recursiveFib(value: Int): Int {
    if (value < 2) return value
    return recursiveFib(value - 1) + recursiveFib(value - 2)
}
