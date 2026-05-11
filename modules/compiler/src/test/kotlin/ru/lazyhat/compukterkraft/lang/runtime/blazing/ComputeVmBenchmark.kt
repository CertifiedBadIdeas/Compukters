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

import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import ru.lazyhat.compukterkraft.lang.runtime.image.CkVmImageAbi
import ru.lazyhat.compukterkraft.lang.runtime.image.compileImage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText
import kotlin.system.measureNanoTime

internal data class ComputeVmBenchmarkReport(
    val samples: Int,
    val workloads: List<ComputeVmBenchmarkWorkloadReport>,
) {
    fun toTsv(): String =
        buildString {
            appendLine(
                "workload\titerations\tchecksum\tsamples\tck_vm_best_ns\tkotlin_jvm_best_ns\trust_native_best_ns\tck_vm_iters_per_sec\tkotlin_jvm_iters_per_sec\trust_native_iters_per_sec\tck_vm_vs_kotlin_slowdown\tck_vm_vs_rust_slowdown",
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
                        workload.rustNativeBestNanos,
                        formatPlain(workload.ckVmIterationsPerSecond),
                        formatPlain(workload.kotlinJvmIterationsPerSecond),
                        formatPlain(workload.rustNativeIterationsPerSecond),
                        formatPlain(workload.ckVmVsKotlinSlowdown),
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
                "CPU-only workloads. The CKL VM runs are timed through the native image VM, with the same work measured as Kotlin/JVM and optimized native Rust baselines.",
            )
            appendLine()
            appendLine(
                "| Workload | Iterations | Checksum | CK VM iter/s | Kotlin/JVM iter/s | Rust native iter/s | CK VM vs Kotlin | CK VM vs Rust |",
            )
            appendLine("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
            workloads.forEach { workload ->
                appendLine(
                    "| ${workload.workloadName} | ${formatInteger(workload.iterations)} | ${workload.checksum} | " +
                        "${formatGrouped(workload.ckVmIterationsPerSecond)} | ${formatGrouped(workload.kotlinJvmIterationsPerSecond)} | " +
                        "${formatGrouped(workload.rustNativeIterationsPerSecond)} | ${formatPlain(workload.ckVmVsKotlinSlowdown)}x | " +
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
    val rustNativeBestNanos: Long,
) {
    val ckVmIterationsPerSecond: Double
        get() = iterationsPerSecond(ckVmBestNanos)

    val kotlinJvmIterationsPerSecond: Double
        get() = iterationsPerSecond(kotlinJvmBestNanos)

    val rustNativeIterationsPerSecond: Double
        get() = iterationsPerSecond(rustNativeBestNanos)

    val ckVmVsKotlinSlowdown: Double
        get() = ckVmBestNanos.toDouble() / kotlinJvmBestNanos.toDouble()

    val ckVmVsRustSlowdown: Double
        get() = ckVmBestNanos.toDouble() / rustNativeBestNanos.toDouble()

    private fun iterationsPerSecond(nanos: Long): Double = iterations.toDouble() * NANOS_PER_SECOND / nanos.toDouble()

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}

private data class ComputeVmBenchmarkWorkloadSpec(
    val name: String,
    val scaleIterations: (Int) -> Int,
    val source: (Int) -> String,
    val runKotlinJvm: (Int) -> Int,
)

private object ComputeVmBenchmarkWorkloads {
    val all: List<ComputeVmBenchmarkWorkloadSpec> =
        listOf(
            ComputeVmBenchmarkWorkloadSpec(
                name = "integer-mix",
                scaleIterations = { base -> base.coerceAtLeast(1) },
                source = ::integerMixSource,
                runKotlinJvm = ::runIntegerMix,
            ),
            ComputeVmBenchmarkWorkloadSpec(
                name = "function-mix",
                scaleIterations = { base -> base.coerceAtLeast(1) },
                source = ::functionMixSource,
                runKotlinJvm = ::runFunctionMix,
            ),
            ComputeVmBenchmarkWorkloadSpec(
                name = "branch-div",
                scaleIterations = { base -> (base / 4).coerceAtLeast(1) },
                source = ::branchDivSource,
                runKotlinJvm = ::runBranchDiv,
            ),
            ComputeVmBenchmarkWorkloadSpec(
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

    private fun recursiveFib(value: Int): Int =
        if (value < 2) {
            value
        } else {
            recursiveFib(value - 1) + recursiveFib(value - 2)
        }
}

internal object ComputeVmBenchmarkRunner {
    private const val INSTRUCTION_BUDGET = Int.MAX_VALUE

    fun run(
        libraryPath: String,
        rustCrateDir: Path,
        iterations: Int,
        warmupIterations: Int,
        samples: Int,
    ): ComputeVmBenchmarkReport {
        require(iterations > 0) { "Benchmark iterations must be positive." }
        require(warmupIterations >= 0) { "Benchmark warmup iterations must be non-negative." }
        require(samples > 0) { "Benchmark samples must be positive." }

        return ComputeVmBenchmarkReport(
            samples = samples,
            workloads =
                ComputeVmBenchmarkWorkloads.all.map { workload ->
                    runWorkload(
                        libraryPath = libraryPath,
                        rustCrateDir = rustCrateDir,
                        workload = workload,
                        iterations = workload.scaleIterations(iterations),
                        warmupIterations = if (warmupIterations == 0) 0 else workload.scaleIterations(warmupIterations),
                        samples = samples,
                    )
                },
        )
    }

    private fun runWorkload(
        libraryPath: String,
        rustCrateDir: Path,
        workload: ComputeVmBenchmarkWorkloadSpec,
        iterations: Int,
        warmupIterations: Int,
        samples: Int,
    ): ComputeVmBenchmarkWorkloadReport {
        val image = compileWorkload(workload, iterations)
        if (warmupIterations > 0) {
            runCkVmSample(libraryPath, compileWorkload(workload, warmupIterations))
            workload.runKotlinJvm(warmupIterations)
            runRustBaseline(workload.name, rustCrateDir, warmupIterations, samples = 1, warmupIterations = 0)
        }

        var checksum = 0
        val ckVmBestNanos =
            List(samples) { sampleIndex ->
                var sampleChecksum = 0
                val elapsed =
                    measureNanoTime {
                        sampleChecksum = runCkVmSample(libraryPath, image)
                    }
                if (sampleIndex == 0) {
                    checksum = sampleChecksum
                } else {
                    check(checksum == sampleChecksum) {
                        "CK VM ${workload.name} checksum changed between samples: $checksum != $sampleChecksum"
                    }
                }
                elapsed
            }.min()

        val kotlinJvm = runKotlinJvmBaseline(workload, iterations, samples)
        check(kotlinJvm.checksum == checksum) {
            "Kotlin/JVM ${workload.name} checksum ${kotlinJvm.checksum} does not match CK VM checksum $checksum"
        }

        val rustNative = runRustBaseline(workload.name, rustCrateDir, iterations, samples, warmupIterations = 0)
        check(rustNative.checksum == checksum) {
            "Rust native ${workload.name} checksum ${rustNative.checksum} does not match CK VM checksum $checksum"
        }

        return ComputeVmBenchmarkWorkloadReport(
            workloadName = workload.name,
            iterations = iterations,
            checksum = checksum,
            ckVmBestNanos = ckVmBestNanos,
            kotlinJvmBestNanos = kotlinJvm.bestNanos,
            rustNativeBestNanos = rustNative.bestNanos,
        )
    }

    private fun compileWorkload(
        workload: ComputeVmBenchmarkWorkloadSpec,
        iterations: Int,
    ): ByteArray {
        val artifact =
            LanguageFrontend()
                .compileImage("${workload.name}.ck", workload.source(iterations))
        val image =
            artifact.image
                ?: error(
                    "Compute benchmark ${workload.name} CKL source did not compile:\n" +
                        artifact.bytecode.analysis.diagnostics
                            .joinToString("\n"),
                )
        return CkVmImageAbi.encode(image)
    }

    private fun runCkVmSample(
        libraryPath: String,
        image: ByteArray,
    ): Int {
        val handle = NativeVmBindings.createImage(libraryPath, image, INSTRUCTION_BUDGET)
        try {
            while (true) {
                when (val signal = NativeVmSignal.decode(NativeVmBindings.runImageUntilSignal(handle))) {
                    is NativeVmSignal.Halt -> {
                        return when (val value = signal.value) {
                            is NativeVmValue.IntValue -> value.value
                            else -> error("Compute benchmark halted with non-Int value: $value")
                        }
                    }

                    NativeVmSignal.Pause -> {
                        Unit
                    }

                    is NativeVmSignal.Error -> {
                        error(signal.message)
                    }

                    else -> {
                        error("Compute benchmark unexpectedly yielded signal: $signal")
                    }
                }
            }
        } finally {
            NativeVmBindings.freeImage(handle)
        }
    }

    private fun runKotlinJvmBaseline(
        workload: ComputeVmBenchmarkWorkloadSpec,
        iterations: Int,
        samples: Int,
    ): KotlinJvmBaselineResult {
        var checksum = 0
        val bestNanos =
            List(samples) { sampleIndex ->
                var sampleChecksum = 0
                val elapsed =
                    measureNanoTime {
                        sampleChecksum = workload.runKotlinJvm(iterations)
                    }
                if (sampleIndex == 0) {
                    checksum = sampleChecksum
                } else {
                    check(checksum == sampleChecksum) {
                        "Kotlin/JVM ${workload.name} checksum changed between samples: $checksum != $sampleChecksum"
                    }
                }
                elapsed
            }.min()
        return KotlinJvmBaselineResult(checksum, bestNanos)
    }

    private fun runRustBaseline(
        workloadName: String,
        rustCrateDir: Path,
        iterations: Int,
        samples: Int,
        warmupIterations: Int,
    ): RustBaselineResult {
        val process =
            ProcessBuilder(
                "cargo",
                "run",
                "--release",
                "--quiet",
                "--example",
                "compute_benchmark_baseline",
                "--",
                workloadName,
                iterations.toString(),
                samples.toString(),
                warmupIterations.toString(),
            ).directory(rustCrateDir.toFile())
                .redirectErrorStream(true)
                .start()
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        check(process.waitFor() == 0) {
            "Rust native $workloadName benchmark failed in ${rustCrateDir.absolutePathString()}:\n$output"
        }
        val lines = output.lines().filter { it.isNotBlank() }
        check(lines.size >= 2 && lines.first() == "checksum\tbest_nanos") {
            "Rust native $workloadName benchmark produced unexpected output:\n$output"
        }
        val parts = lines[1].split('\t')
        check(parts.size == 2) {
            "Rust native $workloadName benchmark produced malformed result line:\n${lines[1]}"
        }
        return RustBaselineResult(
            checksum = parts[0].toInt(),
            bestNanos = parts[1].toLong(),
        )
    }

    private data class RustBaselineResult(
        val checksum: Int,
        val bestNanos: Long,
    )

    private data class KotlinJvmBaselineResult(
        val checksum: Int,
        val bestNanos: Long,
    )
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
