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
    val workloadName: String,
    val iterations: Int,
    val checksum: Int,
    val ckVmBestNanos: Long,
    val rustNativeBestNanos: Long,
    val samples: Int,
) {
    private val ckVmIterationsPerSecond: Double
        get() = iterationsPerSecond(ckVmBestNanos)

    private val rustNativeIterationsPerSecond: Double
        get() = iterationsPerSecond(rustNativeBestNanos)

    private val slowdown: Double
        get() = ckVmBestNanos.toDouble() / rustNativeBestNanos.toDouble()

    fun toTsv(): String =
        buildString {
            appendLine("workload\titerations\tchecksum\tsamples\tck_vm_best_ns\trust_native_best_ns\tck_vm_iters_per_sec\trust_native_iters_per_sec\tslowdown")
            appendLine(
                listOf(
                    workloadName,
                    iterations,
                    checksum,
                    samples,
                    ckVmBestNanos,
                    rustNativeBestNanos,
                    formatPlain(ckVmIterationsPerSecond),
                    formatPlain(rustNativeIterationsPerSecond),
                    formatPlain(slowdown),
                ).joinToString("\t"),
            )
        }

    fun toMarkdown(): String =
        buildString {
            appendLine("# CKL Compute VM Benchmark")
            appendLine()
            appendLine("CPU-only workload. The CKL VM run is timed through the native image VM, and the baseline is the same integer workload compiled as optimized Rust.")
            appendLine()
            appendLine("| Workload | Iterations | Checksum | CK VM iter/s | Rust native iter/s | Slowdown |")
            appendLine("| --- | ---: | ---: | ---: | ---: | ---: |")
            appendLine(
                "| $workloadName | ${formatInteger(iterations)} | $checksum | " +
                    "${formatGrouped(ckVmIterationsPerSecond)} | ${formatGrouped(rustNativeIterationsPerSecond)} | ${formatPlain(slowdown)}x |",
            )
            appendLine()
            appendLine("Best of $samples samples. Higher iter/s is better; lower slowdown is better.")
        }

    private fun iterationsPerSecond(nanos: Long): Double =
        iterations.toDouble() * NANOS_PER_SECOND / nanos.toDouble()

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0

        fun formatPlain(value: Double): String = "%.3f".format(java.util.Locale.US, value)

        fun formatGrouped(value: Double): String = "%,.3f".format(java.util.Locale.US, value)

        fun formatInteger(value: Int): String = "%,d".format(java.util.Locale.US, value)
    }
}

internal object ComputeVmBenchmarkWorkload {
    const val NAME = "integer-mix"

    fun source(iterations: Int): String =
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

        if (warmupIterations > 0) {
            runCkVmSample(libraryPath, warmupIterations)
            runRustBaseline(rustCrateDir, warmupIterations, samples = 1, warmupIterations = 0)
        }

        var checksum = 0
        val ckVmBestNanos =
            List(samples) { sampleIndex ->
                var sampleChecksum = 0
                val elapsed =
                    measureNanoTime {
                        sampleChecksum = runCkVmSample(libraryPath, iterations)
                    }
                if (sampleIndex == 0) {
                    checksum = sampleChecksum
                } else {
                    check(checksum == sampleChecksum) {
                        "CK VM benchmark checksum changed between samples: $checksum != $sampleChecksum"
                    }
                }
                elapsed
            }.min()

        val rustNative = runRustBaseline(rustCrateDir, iterations, samples, warmupIterations = 0)
        check(rustNative.checksum == checksum) {
            "Rust native benchmark checksum ${rustNative.checksum} does not match CK VM checksum $checksum"
        }

        return ComputeVmBenchmarkReport(
            workloadName = ComputeVmBenchmarkWorkload.NAME,
            iterations = iterations,
            checksum = checksum,
            ckVmBestNanos = ckVmBestNanos,
            rustNativeBestNanos = rustNative.bestNanos,
            samples = samples,
        )
    }

    private fun runCkVmSample(
        libraryPath: String,
        iterations: Int,
    ): Int {
        val artifact =
            LanguageFrontend()
                .compileImage("compute_benchmark.ck", ComputeVmBenchmarkWorkload.source(iterations))
        val image =
            artifact.image
                ?: error(
                    "Compute benchmark CKL source did not compile:\n" +
                        artifact.bytecode.analysis.diagnostics.joinToString("\n"),
                )
        val handle = NativeVmBindings.createImage(libraryPath, CkVmImageAbi.encode(image), INSTRUCTION_BUDGET)
        try {
            while (true) {
                when (val signal = NativeVmSignal.decode(NativeVmBindings.runImageUntilSignal(handle))) {
                    is NativeVmSignal.Halt ->
                        return when (val value = signal.value) {
                            is NativeVmValue.IntValue -> value.value
                            else -> error("Compute benchmark halted with non-Int value: $value")
                        }
                    NativeVmSignal.Pause -> Unit
                    is NativeVmSignal.Error -> error(signal.message)
                    else -> error("Compute benchmark unexpectedly yielded signal: $signal")
                }
            }
        } finally {
            NativeVmBindings.freeImage(handle)
        }
    }

    private fun runRustBaseline(
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
                iterations.toString(),
                samples.toString(),
                warmupIterations.toString(),
            ).directory(rustCrateDir.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) {
            "Rust native benchmark failed in ${rustCrateDir.absolutePathString()}:\n$output"
        }
        val lines = output.lines().filter { it.isNotBlank() }
        check(lines.size >= 2 && lines.first() == "checksum\tbest_nanos") {
            "Rust native benchmark produced unexpected output:\n$output"
        }
        val parts = lines[1].split('\t')
        check(parts.size == 2) {
            "Rust native benchmark produced malformed result line:\n${lines[1]}"
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
