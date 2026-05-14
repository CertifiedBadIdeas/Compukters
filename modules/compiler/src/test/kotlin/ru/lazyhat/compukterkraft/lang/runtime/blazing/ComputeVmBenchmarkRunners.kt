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

import ru.lazyhat.compukterkraft.lang.runtime.image.low.RuxLowVmFunction
import ru.lazyhat.compukterkraft.lang.runtime.image.low.RuxLowVmImage
import ru.lazyhat.compukterkraft.lang.runtime.image.low.RuxLowVmImageAbi
import ru.lazyhat.compukterkraft.lang.runtime.image.low.RuxLowVmInstruction
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.system.measureNanoTime

internal data class ComputeVmBenchmarkRunResult(
    val checksum: Int,
    val bestNanos: Long,
    val lowVmMetrics: NativeLowImageVmMetrics = NativeLowImageVmMetrics.EMPTY,
)

private data class ComputeVmBenchmarkSampleResult(
    val checksum: Int,
    val lowVmMetrics: NativeLowImageVmMetrics = NativeLowImageVmMetrics.EMPTY,
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

internal class LowVmComputeBenchmarkRunner(
    private val libraryPath: String,
) : ComputeVmBenchmarkWorkloadRunner {
    override val name: String = "Low-level VM"

    fun supports(workload: ComputeVmBenchmarkWorkloadSpec): Boolean =
        workload.name == "integer-mix" ||
            workload.name == "function-mix" ||
            workload.name == "branch-div" ||
            workload.name == "recursive-fib"

    override fun warmUp(
        workload: ComputeVmBenchmarkWorkloadSpec,
        iterations: Int,
    ) {
        if (iterations > 0 && supports(workload)) {
            run(workload, iterations, samples = 1)
        }
    }

    override fun run(
        workload: ComputeVmBenchmarkWorkloadSpec,
        iterations: Int,
        samples: Int,
    ): ComputeVmBenchmarkRunResult {
        require(supports(workload)) { "Low-level VM benchmark does not support ${workload.name} yet." }
        val image = lowImageFor(workload, iterations)
        return bestOf(samples, workload) {
            runImage(image)
        }
    }

    private fun lowImageFor(
        workload: ComputeVmBenchmarkWorkloadSpec,
        iterations: Int,
    ): ByteArray =
        when (workload.name) {
            "integer-mix" -> RuxLowVmImageAbi.encode(integerMixImage(iterations))
            "function-mix" -> RuxLowVmImageAbi.encode(functionMixImage(iterations))
            "branch-div" -> RuxLowVmImageAbi.encode(branchDivImage(iterations))
            "recursive-fib" -> RuxLowVmImageAbi.encode(recursiveFibImage(iterations))
            else -> error("Low-level VM benchmark does not support ${workload.name} yet.")
        }

    private fun runImage(image: ByteArray): ComputeVmBenchmarkSampleResult {
        val handle = NativeVmBindings.createLowImage(libraryPath, image, LOW_VM_SLICE_BUDGET_NANOS)
        try {
            while (true) {
                when (val signal = NativeVmBindings.runLowImageUntilSignal(handle)) {
                    is NativeLowImageVmSignal.HaltI32 ->
                        return ComputeVmBenchmarkSampleResult(
                            checksum = signal.value,
                            lowVmMetrics = NativeVmBindings.lowImageMetrics(handle),
                        )
                    NativeLowImageVmSignal.Pause -> Unit
                    else -> error("Low-level compute benchmark unexpectedly halted with signal: $signal")
                }
            }
        } finally {
            NativeVmBindings.freeLowImage(handle)
        }
    }

    private fun integerMixImage(iterations: Int): RuxLowVmImage {
        val instructions = mutableListOf<RuxLowVmInstruction>()
        instructions += RuxLowVmInstruction.I32Const(dst = 0, value = iterations)
        instructions += RuxLowVmInstruction.I32Const(dst = 1, value = 305_419_896)
        instructions += RuxLowVmInstruction.I32Const(dst = 2, value = -1_640_531_527)
        instructions += RuxLowVmInstruction.I32Const(dst = 3, value = 0)
        instructions += RuxLowVmInstruction.I32Const(dst = 4, value = 1)
        instructions += RuxLowVmInstruction.I32Const(dst = 5, value = 1_664_525)
        instructions += RuxLowVmInstruction.I32Const(dst = 6, value = 1_013_904_223)
        instructions += RuxLowVmInstruction.I32Const(dst = 7, value = 16)
        instructions += RuxLowVmInstruction.I32Const(dst = 8, value = 5)
        instructions += RuxLowVmInstruction.I32Const(dst = 9, value = 31)
        instructions += RuxLowVmInstruction.I32Const(dst = 10, value = 3)

        val loopStart = instructions.size
        instructions += RuxLowVmInstruction.I32Lt(dst = 19, lhs = 3, rhs = 0)
        val exitJumpIndex = instructions.size
        instructions += RuxLowVmInstruction.JumpIfFalse(cond = 19, target = -1)
        instructions += RuxLowVmInstruction.I32Mul(dst = 11, lhs = 1, rhs = 5)
        instructions += RuxLowVmInstruction.I32Add(dst = 1, lhs = 11, rhs = 6)
        instructions += RuxLowVmInstruction.I32Shr(dst = 12, lhs = 1, rhs = 7)
        instructions += RuxLowVmInstruction.I32BitXor(dst = 13, lhs = 1, rhs = 12)
        instructions += RuxLowVmInstruction.I32Add(dst = 14, lhs = 2, rhs = 13)
        instructions += RuxLowVmInstruction.I32Shl(dst = 15, lhs = 2, rhs = 8)
        instructions += RuxLowVmInstruction.I32BitXor(dst = 2, lhs = 14, rhs = 15)
        instructions += RuxLowVmInstruction.I32Mul(dst = 16, lhs = 3, rhs = 9)
        instructions += RuxLowVmInstruction.I32Shr(dst = 17, lhs = 13, rhs = 10)
        instructions += RuxLowVmInstruction.I32BitXor(dst = 18, lhs = 16, rhs = 17)
        instructions += RuxLowVmInstruction.I32Add(dst = 2, lhs = 2, rhs = 18)
        instructions += RuxLowVmInstruction.I32Add(dst = 3, lhs = 3, rhs = 4)
        instructions += RuxLowVmInstruction.Jump(target = loopStart)
        instructions[exitJumpIndex] = RuxLowVmInstruction.JumpIfFalse(cond = 19, target = instructions.size)
        instructions += RuxLowVmInstruction.ReturnI32(2)

        return RuxLowVmImage(
            languageVersion = "rux-low-1",
            memorySize = 1024u,
            entryFunctionIndex = 0,
            functions =
                listOf(
                    RuxLowVmFunction(
                        name = "main",
                        registerCount = 20,
                        parameters = emptyList(),
                        instructions = instructions,
                    ),
                ),
        )
    }

    private fun branchDivImage(iterations: Int): RuxLowVmImage {
        val instructions = mutableListOf<RuxLowVmInstruction>()
        instructions += RuxLowVmInstruction.I32Const(dst = 0, value = iterations + 1)
        instructions += RuxLowVmInstruction.I32Const(dst = 1, value = 7)
        instructions += RuxLowVmInstruction.I32Const(dst = 2, value = 1)
        instructions += RuxLowVmInstruction.I32Const(dst = 3, value = 1)
        instructions += RuxLowVmInstruction.I32Const(dst = 4, value = 11)
        instructions += RuxLowVmInstruction.I32Const(dst = 5, value = 3)
        instructions += RuxLowVmInstruction.I32Const(dst = 6, value = 5)
        instructions += RuxLowVmInstruction.I32Const(dst = 7, value = 7)
        instructions += RuxLowVmInstruction.I32Const(dst = 8, value = 17)

        val loopStart = instructions.size
        instructions += RuxLowVmInstruction.I32Lt(dst = 18, lhs = 2, rhs = 0)
        val exitJumpIndex = instructions.size
        instructions += RuxLowVmInstruction.JumpIfFalse(cond = 18, target = -1)
        instructions += RuxLowVmInstruction.I32Div(dst = 9, lhs = 2, rhs = 4)
        instructions += RuxLowVmInstruction.I32Mul(dst = 11, lhs = 9, rhs = 4)
        instructions += RuxLowVmInstruction.I32Sub(dst = 10, lhs = 2, rhs = 11)
        instructions += RuxLowVmInstruction.I32Lt(dst = 18, lhs = 10, rhs = 3)
        val modNonZeroJumpIndex = instructions.size
        instructions += RuxLowVmInstruction.JumpIfFalse(cond = 18, target = -1)
        instructions += RuxLowVmInstruction.I32Div(dst = 15, lhs = 2, rhs = 5)
        instructions += RuxLowVmInstruction.I32Add(dst = 1, lhs = 1, rhs = 15)
        val firstBranchDoneJumpIndex = instructions.size
        instructions += RuxLowVmInstruction.Jump(target = -1)

        val modNonZeroStart = instructions.size
        instructions[modNonZeroJumpIndex] = RuxLowVmInstruction.JumpIfFalse(cond = 18, target = modNonZeroStart)
        instructions += RuxLowVmInstruction.I32Lt(dst = 18, lhs = 10, rhs = 6)
        val highModJumpIndex = instructions.size
        instructions += RuxLowVmInstruction.JumpIfFalse(cond = 18, target = -1)
        instructions += RuxLowVmInstruction.I32Mul(dst = 12, lhs = 2, rhs = 8)
        instructions += RuxLowVmInstruction.I32BitXor(dst = 17, lhs = 1, rhs = 12)
        instructions += RuxLowVmInstruction.I32Div(dst = 9, lhs = 2, rhs = 7)
        instructions += RuxLowVmInstruction.I32Mul(dst = 11, lhs = 9, rhs = 7)
        instructions += RuxLowVmInstruction.I32Sub(dst = 13, lhs = 2, rhs = 11)
        instructions += RuxLowVmInstruction.I32Add(dst = 1, lhs = 17, rhs = 13)
        val secondBranchDoneJumpIndex = instructions.size
        instructions += RuxLowVmInstruction.Jump(target = -1)

        val highModStart = instructions.size
        instructions[highModJumpIndex] = RuxLowVmInstruction.JumpIfFalse(cond = 18, target = highModStart)
        instructions += RuxLowVmInstruction.I32Add(dst = 14, lhs = 10, rhs = 3)
        instructions += RuxLowVmInstruction.I32Div(dst = 15, lhs = 2, rhs = 14)
        instructions += RuxLowVmInstruction.I32Sub(dst = 17, lhs = 1, rhs = 15)
        instructions += RuxLowVmInstruction.I32Shl(dst = 16, lhs = 1, rhs = 3)
        instructions += RuxLowVmInstruction.I32Add(dst = 1, lhs = 17, rhs = 16)

        val afterIf = instructions.size
        instructions[firstBranchDoneJumpIndex] = RuxLowVmInstruction.Jump(target = afterIf)
        instructions[secondBranchDoneJumpIndex] = RuxLowVmInstruction.Jump(target = afterIf)
        instructions += RuxLowVmInstruction.I32Add(dst = 2, lhs = 2, rhs = 3)
        instructions += RuxLowVmInstruction.Jump(target = loopStart)
        instructions[exitJumpIndex] = RuxLowVmInstruction.JumpIfFalse(cond = 18, target = instructions.size)
        instructions += RuxLowVmInstruction.ReturnI32(1)

        return RuxLowVmImage(
            languageVersion = "rux-low-1",
            memorySize = 1024u,
            entryFunctionIndex = 0,
            functions =
                listOf(
                    RuxLowVmFunction(
                        name = "main",
                        registerCount = 19,
                        parameters = emptyList(),
                        instructions = instructions,
                    ),
                ),
        )
    }

    private fun functionMixImage(iterations: Int): RuxLowVmImage =
        RuxLowVmImage(
            languageVersion = "rux-low-1",
            memorySize = 1024u,
            entryFunctionIndex = 0,
            functions =
                listOf(
                    RuxLowVmFunction(
                        name = "main",
                        registerCount = 6,
                        parameters = emptyList(),
                        instructions = functionMixMainInstructions(iterations),
                    ),
                    RuxLowVmFunction(
                        name = "mixA",
                        registerCount = 11,
                        parameters = listOf(0, 1),
                        instructions =
                            listOf(
                                RuxLowVmInstruction.I32Const(dst = 2, value = 17),
                                RuxLowVmInstruction.I32Const(dst = 3, value = 3),
                                RuxLowVmInstruction.I32Const(dst = 4, value = 1),
                                RuxLowVmInstruction.I32Mul(dst = 5, lhs = 1, rhs = 2),
                                RuxLowVmInstruction.I32Add(dst = 6, lhs = 0, rhs = 5),
                                RuxLowVmInstruction.I32Shl(dst = 7, lhs = 0, rhs = 3),
                                RuxLowVmInstruction.I32BitXor(dst = 8, lhs = 6, rhs = 7),
                                RuxLowVmInstruction.I32Shr(dst = 9, lhs = 1, rhs = 4),
                                RuxLowVmInstruction.I32Add(dst = 10, lhs = 8, rhs = 9),
                                RuxLowVmInstruction.ReturnI32(10),
                            ),
                    ),
                    RuxLowVmFunction(
                        name = "mixB",
                        registerCount = 11,
                        parameters = listOf(0, 1),
                        instructions =
                            listOf(
                                RuxLowVmInstruction.I32Const(dst = 2, value = 131),
                                RuxLowVmInstruction.I32Const(dst = 3, value = 2),
                                RuxLowVmInstruction.I32Const(dst = 4, value = 4),
                                RuxLowVmInstruction.I32Mul(dst = 5, lhs = 1, rhs = 2),
                                RuxLowVmInstruction.I32BitXor(dst = 6, lhs = 0, rhs = 5),
                                RuxLowVmInstruction.I32Shr(dst = 7, lhs = 0, rhs = 3),
                                RuxLowVmInstruction.I32Add(dst = 8, lhs = 6, rhs = 7),
                                RuxLowVmInstruction.I32Shl(dst = 9, lhs = 1, rhs = 4),
                                RuxLowVmInstruction.I32BitXor(dst = 10, lhs = 8, rhs = 9),
                                RuxLowVmInstruction.ReturnI32(10),
                            ),
                    ),
                ),
        )

    private fun functionMixMainInstructions(iterations: Int): List<RuxLowVmInstruction> {
        val instructions = mutableListOf<RuxLowVmInstruction>()
        instructions += RuxLowVmInstruction.I32Const(dst = 0, value = iterations)
        instructions += RuxLowVmInstruction.I32Const(dst = 1, value = 324_508_639)
        instructions += RuxLowVmInstruction.I32Const(dst = 2, value = 0)
        instructions += RuxLowVmInstruction.I32Const(dst = 3, value = 1)
        val loopStart = instructions.size
        instructions += RuxLowVmInstruction.I32Lt(dst = 5, lhs = 2, rhs = 0)
        val exitJumpIndex = instructions.size
        instructions += RuxLowVmInstruction.JumpIfFalse(cond = 5, target = -1)
        instructions +=
            RuxLowVmInstruction.CallStatic(
                returnRegister = 4,
                functionIndex = 1,
                arguments = listOf(1, 2),
            )
        instructions +=
            RuxLowVmInstruction.CallStatic(
                returnRegister = 1,
                functionIndex = 2,
                arguments = listOf(4, 2),
            )
        instructions += RuxLowVmInstruction.I32Add(dst = 2, lhs = 2, rhs = 3)
        instructions += RuxLowVmInstruction.Jump(target = loopStart)
        instructions[exitJumpIndex] = RuxLowVmInstruction.JumpIfFalse(cond = 5, target = instructions.size)
        instructions += RuxLowVmInstruction.ReturnI32(1)
        return instructions
    }

    private fun recursiveFibImage(iterations: Int): RuxLowVmImage =
        RuxLowVmImage(
            languageVersion = "rux-low-1",
            memorySize = 1024u,
            entryFunctionIndex = 0,
            functions =
                listOf(
                    RuxLowVmFunction(
                        name = "main",
                        registerCount = 13,
                        parameters = emptyList(),
                        instructions = recursiveFibMainInstructions(iterations),
                    ),
                    RuxLowVmFunction(
                        name = "fib",
                        registerCount = 8,
                        parameters = listOf(0),
                        instructions = fibFunctionInstructions(),
                    ),
                ),
        )

    private fun recursiveFibMainInstructions(iterations: Int): List<RuxLowVmInstruction> {
        val instructions = mutableListOf<RuxLowVmInstruction>()
        instructions += RuxLowVmInstruction.I32Const(dst = 0, value = iterations)
        instructions += RuxLowVmInstruction.I32Const(dst = 1, value = 0)
        instructions += RuxLowVmInstruction.I32Const(dst = 2, value = 0)
        instructions += RuxLowVmInstruction.I32Const(dst = 3, value = 1)
        instructions += RuxLowVmInstruction.I32Const(dst = 4, value = 6)
        instructions += RuxLowVmInstruction.I32Const(dst = 5, value = 10)
        instructions += RuxLowVmInstruction.I32Const(dst = 6, value = 31)
        val loopStart = instructions.size
        instructions += RuxLowVmInstruction.I32Lt(dst = 12, lhs = 2, rhs = 0)
        val exitJumpIndex = instructions.size
        instructions += RuxLowVmInstruction.JumpIfFalse(cond = 12, target = -1)
        instructions += RuxLowVmInstruction.I32Div(dst = 7, lhs = 2, rhs = 4)
        instructions += RuxLowVmInstruction.I32Mul(dst = 8, lhs = 7, rhs = 4)
        instructions += RuxLowVmInstruction.I32Sub(dst = 9, lhs = 2, rhs = 8)
        instructions += RuxLowVmInstruction.I32Add(dst = 9, lhs = 9, rhs = 5)
        instructions +=
            RuxLowVmInstruction.CallStatic(
                returnRegister = 10,
                functionIndex = 1,
                arguments = listOf(9),
            )
        instructions += RuxLowVmInstruction.I32Mul(dst = 11, lhs = 2, rhs = 6)
        instructions += RuxLowVmInstruction.I32BitXor(dst = 10, lhs = 10, rhs = 11)
        instructions += RuxLowVmInstruction.I32Add(dst = 1, lhs = 1, rhs = 10)
        instructions += RuxLowVmInstruction.I32Add(dst = 2, lhs = 2, rhs = 3)
        instructions += RuxLowVmInstruction.Jump(target = loopStart)
        instructions[exitJumpIndex] = RuxLowVmInstruction.JumpIfFalse(cond = 12, target = instructions.size)
        instructions += RuxLowVmInstruction.ReturnI32(1)
        return instructions
    }

    private fun fibFunctionInstructions(): List<RuxLowVmInstruction> =
        listOf(
            RuxLowVmInstruction.I32Const(dst = 1, value = 2),
            RuxLowVmInstruction.I32Lt(dst = 7, lhs = 0, rhs = 1),
            RuxLowVmInstruction.JumpIfFalse(cond = 7, target = 4),
            RuxLowVmInstruction.ReturnI32(0),
            RuxLowVmInstruction.I32Const(dst = 2, value = 1),
            RuxLowVmInstruction.I32Sub(dst = 3, lhs = 0, rhs = 2),
            RuxLowVmInstruction.CallStatic(
                returnRegister = 4,
                functionIndex = 1,
                arguments = listOf(3),
            ),
            RuxLowVmInstruction.I32Const(dst = 2, value = 2),
            RuxLowVmInstruction.I32Sub(dst = 3, lhs = 0, rhs = 2),
            RuxLowVmInstruction.CallStatic(
                returnRegister = 5,
                functionIndex = 1,
                arguments = listOf(3),
            ),
            RuxLowVmInstruction.I32Add(dst = 6, lhs = 4, rhs = 5),
            RuxLowVmInstruction.ReturnI32(6),
        )

    private companion object {
        const val LOW_VM_SLICE_BUDGET_NANOS = Int.MAX_VALUE
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
            ComputeVmBenchmarkSampleResult(workload.runKotlinJvm(iterations))
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
    runSample: () -> ComputeVmBenchmarkSampleResult,
): ComputeVmBenchmarkRunResult {
    require(samples > 0) { "Benchmark samples must be positive." }
    var checksum = 0
    var bestNanos = Long.MAX_VALUE
    var bestLowVmMetrics = NativeLowImageVmMetrics.EMPTY
    repeat(samples) { sampleIndex ->
        var sampleResult = ComputeVmBenchmarkSampleResult(0)
        val elapsed =
            measureNanoTime {
                sampleResult = runSample()
            }
        if (sampleIndex == 0) {
            checksum = sampleResult.checksum
        } else {
            check(checksum == sampleResult.checksum) {
                "${workload.name} checksum changed between samples: $checksum != ${sampleResult.checksum}"
            }
        }
        if (elapsed < bestNanos) {
            bestNanos = elapsed
            bestLowVmMetrics = sampleResult.lowVmMetrics
        }
    }
    return ComputeVmBenchmarkRunResult(checksum, bestNanos, bestLowVmMetrics)
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
