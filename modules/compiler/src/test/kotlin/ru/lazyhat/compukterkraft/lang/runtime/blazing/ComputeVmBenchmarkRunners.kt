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
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.system.measureNanoTime

internal data class ComputeVmBenchmarkRunResult(
    val checksum: Int,
    val bestNanos: Long,
)

internal interface ComputeVmBenchmarkWorkloadRunner {
    val name: String

    fun warmUp(
        workload: ComputeVmBenchmarkWorkloadSpec,
        iterations: Int,
    ) {
        if (iterations > 0) {
            run(workload, iterations, samples = 1)
        }
    }

    fun run(
        workload: ComputeVmBenchmarkWorkloadSpec,
        iterations: Int,
        samples: Int,
    ): ComputeVmBenchmarkRunResult
}

internal class CkVmComputeBenchmarkRunner(
    private val libraryPath: String,
) : ComputeVmBenchmarkWorkloadRunner {
    override val name: String = "CK VM"

    override fun run(
        workload: ComputeVmBenchmarkWorkloadSpec,
        iterations: Int,
        samples: Int,
    ): ComputeVmBenchmarkRunResult {
        val image = compileWorkload(workload, iterations)
        return bestOf(samples, workload) {
            runImage(image)
        }
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

    private fun runImage(image: ByteArray): Int {
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

    private companion object {
        const val INSTRUCTION_BUDGET = Int.MAX_VALUE
    }
}

internal object KotlinJvmComputeBenchmarkRunner : ComputeVmBenchmarkWorkloadRunner {
    override val name: String = "Kotlin/JVM"

    override fun run(
        workload: ComputeVmBenchmarkWorkloadSpec,
        iterations: Int,
        samples: Int,
    ): ComputeVmBenchmarkRunResult =
        bestOf(samples, workload) {
            workload.runKotlinJvm(iterations)
        }
}

internal class PythonComputeBenchmarkRunner(
    private val pythonCommand: String,
    private val scriptPath: Path,
) : ComputeVmBenchmarkWorkloadRunner {
    override val name: String = "Python"

    override fun run(
        workload: ComputeVmBenchmarkWorkloadSpec,
        iterations: Int,
        samples: Int,
    ): ComputeVmBenchmarkRunResult =
        runProcessBackedBaseline(
            workload = workload,
            command =
                listOf(
                    pythonCommand,
                    scriptPath.toAbsolutePath().toString(),
                    workload.name,
                    iterations.toString(),
                    samples.toString(),
                ),
            workingDirectory = scriptPath.parent,
        )
}

internal class RustNativeComputeBenchmarkRunner(
    private val rustCrateDir: Path,
) : ComputeVmBenchmarkWorkloadRunner {
    override val name: String = "Rust native"

    override fun run(
        workload: ComputeVmBenchmarkWorkloadSpec,
        iterations: Int,
        samples: Int,
    ): ComputeVmBenchmarkRunResult =
        runProcessBackedBaseline(
            workload = workload,
            command =
                listOf(
                    "cargo",
                    "run",
                    "--release",
                    "--quiet",
                    "--example",
                    "compute_benchmark_baseline",
                    "--",
                    workload.name,
                    iterations.toString(),
                    samples.toString(),
                ),
            workingDirectory = rustCrateDir,
        )
}

private fun bestOf(
    samples: Int,
    workload: ComputeVmBenchmarkWorkloadSpec,
    runSample: () -> Int,
): ComputeVmBenchmarkRunResult {
    require(samples > 0) { "Benchmark samples must be positive." }
    var checksum = 0
    val bestNanos =
        List(samples) { sampleIndex ->
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
    return ComputeVmBenchmarkRunResult(checksum, bestNanos)
}

private fun runProcessBackedBaseline(
    workload: ComputeVmBenchmarkWorkloadSpec,
    command: List<String>,
    workingDirectory: Path,
): ComputeVmBenchmarkRunResult {
    val process =
        ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .start()
    val output =
        process.inputStream
            .bufferedReader()
            .readText()
            .trim()
    check(process.waitFor() == 0) {
        "${workload.name} process-backed benchmark failed in ${workingDirectory.absolutePathString()}:\n$output"
    }
    val lines = output.lines().filter { it.isNotBlank() }
    check(lines.size >= 2 && lines.first() == "checksum\tbest_nanos") {
        "${workload.name} process-backed benchmark produced unexpected output:\n$output"
    }
    val parts = lines[1].split('\t')
    check(parts.size == 2) {
        "${workload.name} process-backed benchmark produced malformed result line:\n${lines[1]}"
    }
    return ComputeVmBenchmarkRunResult(
        checksum = parts[0].toInt(),
        bestNanos = parts[1].toLong(),
    )
}
