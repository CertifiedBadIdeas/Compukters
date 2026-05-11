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

import ru.lazyhat.compukterkraft.lang.runtime.image.low.CkLowVmFunction
import ru.lazyhat.compukterkraft.lang.runtime.image.low.CkLowVmImage
import ru.lazyhat.compukterkraft.lang.runtime.image.low.CkLowVmImageAbi
import ru.lazyhat.compukterkraft.lang.runtime.image.low.CkLowVmInstruction
import ru.lazyhat.compukterkraft.lang.runtime.image.low.CkLowVmRegister
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.system.measureNanoTime

internal data class ComputeVmBenchmarkRunResult(
    val checksum: Int,
    val bestNanos: Long,
)

private data class ComputeVmBenchmarkSampleResult(
    val checksum: Int,
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
            "integer-mix" -> CkLowVmImageAbi.encode(integerMixImage(iterations))
            "function-mix" -> CkLowVmImageAbi.encode(functionMixImage(iterations))
            "branch-div" -> CkLowVmImageAbi.encode(branchDivImage(iterations))
            "recursive-fib" -> CkLowVmImageAbi.encode(recursiveFibImage(iterations))
            else -> error("Low-level VM benchmark does not support ${workload.name} yet.")
        }

    private fun runImage(image: ByteArray): ComputeVmBenchmarkSampleResult {
        val handle = NativeVmBindings.createLowImage(libraryPath, image, INSTRUCTION_BUDGET)
        try {
            while (true) {
                when (val signal = NativeVmBindings.runLowImageUntilSignal(handle)) {
                    is NativeLowImageVmSignal.HaltI32 -> return ComputeVmBenchmarkSampleResult(signal.value)
                    NativeLowImageVmSignal.Pause -> Unit
                    else -> error("Low-level compute benchmark unexpectedly halted with signal: $signal")
                }
            }
        } finally {
            NativeVmBindings.freeLowImage(handle)
        }
    }

    private fun integerMixImage(iterations: Int): CkLowVmImage {
        val instructions = mutableListOf<CkLowVmInstruction>()
        instructions += CkLowVmInstruction.I32Const(dst = 0, value = iterations)
        instructions += CkLowVmInstruction.I32Const(dst = 1, value = 305_419_896)
        instructions += CkLowVmInstruction.I32Const(dst = 2, value = -1_640_531_527)
        instructions += CkLowVmInstruction.I32Const(dst = 3, value = 0)
        instructions += CkLowVmInstruction.I32Const(dst = 4, value = 1)
        instructions += CkLowVmInstruction.I32Const(dst = 5, value = 1_664_525)
        instructions += CkLowVmInstruction.I32Const(dst = 6, value = 1_013_904_223)
        instructions += CkLowVmInstruction.I32Const(dst = 7, value = 16)
        instructions += CkLowVmInstruction.I32Const(dst = 8, value = 5)
        instructions += CkLowVmInstruction.I32Const(dst = 9, value = 31)
        instructions += CkLowVmInstruction.I32Const(dst = 10, value = 3)

        val loopStart = instructions.size
        instructions += CkLowVmInstruction.I32Lt(dst = 0, lhs = 3, rhs = 0)
        val exitJumpIndex = instructions.size
        instructions += CkLowVmInstruction.JumpIfFalse(cond = 0, target = -1)
        instructions += CkLowVmInstruction.I32Mul(dst = 11, lhs = 1, rhs = 5)
        instructions += CkLowVmInstruction.I32Add(dst = 1, lhs = 11, rhs = 6)
        instructions += CkLowVmInstruction.I32Shr(dst = 12, lhs = 1, rhs = 7)
        instructions += CkLowVmInstruction.I32BitXor(dst = 13, lhs = 1, rhs = 12)
        instructions += CkLowVmInstruction.I32Add(dst = 14, lhs = 2, rhs = 13)
        instructions += CkLowVmInstruction.I32Shl(dst = 15, lhs = 2, rhs = 8)
        instructions += CkLowVmInstruction.I32BitXor(dst = 2, lhs = 14, rhs = 15)
        instructions += CkLowVmInstruction.I32Mul(dst = 16, lhs = 3, rhs = 9)
        instructions += CkLowVmInstruction.I32Shr(dst = 17, lhs = 13, rhs = 10)
        instructions += CkLowVmInstruction.I32BitXor(dst = 18, lhs = 16, rhs = 17)
        instructions += CkLowVmInstruction.I32Add(dst = 2, lhs = 2, rhs = 18)
        instructions += CkLowVmInstruction.I32Add(dst = 3, lhs = 3, rhs = 4)
        instructions += CkLowVmInstruction.Jump(target = loopStart)
        instructions[exitJumpIndex] = CkLowVmInstruction.JumpIfFalse(cond = 0, target = instructions.size)
        instructions += CkLowVmInstruction.Return(CkLowVmRegister.I32(2))

        return CkLowVmImage(
            languageVersion = "ckl-low-1",
            memorySize = 1024u,
            entryFunctionIndex = 0,
            functions =
                listOf(
                    CkLowVmFunction(
                        name = "main",
                        i32RegisterCount = 19,
                        i64RegisterCount = 0,
                        addrRegisterCount = 0,
                        boolRegisterCount = 1,
                        parameters = emptyList(),
                        instructions = instructions,
                    ),
                ),
        )
    }

    private fun branchDivImage(iterations: Int): CkLowVmImage {
        val instructions = mutableListOf<CkLowVmInstruction>()
        instructions += CkLowVmInstruction.I32Const(dst = 0, value = iterations + 1)
        instructions += CkLowVmInstruction.I32Const(dst = 1, value = 7)
        instructions += CkLowVmInstruction.I32Const(dst = 2, value = 1)
        instructions += CkLowVmInstruction.I32Const(dst = 3, value = 1)
        instructions += CkLowVmInstruction.I32Const(dst = 4, value = 11)
        instructions += CkLowVmInstruction.I32Const(dst = 5, value = 3)
        instructions += CkLowVmInstruction.I32Const(dst = 6, value = 5)
        instructions += CkLowVmInstruction.I32Const(dst = 7, value = 7)
        instructions += CkLowVmInstruction.I32Const(dst = 8, value = 17)

        val loopStart = instructions.size
        instructions += CkLowVmInstruction.I32Lt(dst = 0, lhs = 2, rhs = 0)
        val exitJumpIndex = instructions.size
        instructions += CkLowVmInstruction.JumpIfFalse(cond = 0, target = -1)
        instructions += CkLowVmInstruction.I32Div(dst = 9, lhs = 2, rhs = 4)
        instructions += CkLowVmInstruction.I32Mul(dst = 11, lhs = 9, rhs = 4)
        instructions += CkLowVmInstruction.I32Sub(dst = 10, lhs = 2, rhs = 11)
        instructions += CkLowVmInstruction.I32Lt(dst = 0, lhs = 10, rhs = 3)
        val modNonZeroJumpIndex = instructions.size
        instructions += CkLowVmInstruction.JumpIfFalse(cond = 0, target = -1)
        instructions += CkLowVmInstruction.I32Div(dst = 15, lhs = 2, rhs = 5)
        instructions += CkLowVmInstruction.I32Add(dst = 1, lhs = 1, rhs = 15)
        val firstBranchDoneJumpIndex = instructions.size
        instructions += CkLowVmInstruction.Jump(target = -1)

        val modNonZeroStart = instructions.size
        instructions[modNonZeroJumpIndex] = CkLowVmInstruction.JumpIfFalse(cond = 0, target = modNonZeroStart)
        instructions += CkLowVmInstruction.I32Lt(dst = 0, lhs = 10, rhs = 6)
        val highModJumpIndex = instructions.size
        instructions += CkLowVmInstruction.JumpIfFalse(cond = 0, target = -1)
        instructions += CkLowVmInstruction.I32Mul(dst = 12, lhs = 2, rhs = 8)
        instructions += CkLowVmInstruction.I32BitXor(dst = 17, lhs = 1, rhs = 12)
        instructions += CkLowVmInstruction.I32Div(dst = 9, lhs = 2, rhs = 7)
        instructions += CkLowVmInstruction.I32Mul(dst = 11, lhs = 9, rhs = 7)
        instructions += CkLowVmInstruction.I32Sub(dst = 13, lhs = 2, rhs = 11)
        instructions += CkLowVmInstruction.I32Add(dst = 1, lhs = 17, rhs = 13)
        val secondBranchDoneJumpIndex = instructions.size
        instructions += CkLowVmInstruction.Jump(target = -1)

        val highModStart = instructions.size
        instructions[highModJumpIndex] = CkLowVmInstruction.JumpIfFalse(cond = 0, target = highModStart)
        instructions += CkLowVmInstruction.I32Add(dst = 14, lhs = 10, rhs = 3)
        instructions += CkLowVmInstruction.I32Div(dst = 15, lhs = 2, rhs = 14)
        instructions += CkLowVmInstruction.I32Sub(dst = 17, lhs = 1, rhs = 15)
        instructions += CkLowVmInstruction.I32Shl(dst = 16, lhs = 1, rhs = 3)
        instructions += CkLowVmInstruction.I32Add(dst = 1, lhs = 17, rhs = 16)

        val afterIf = instructions.size
        instructions[firstBranchDoneJumpIndex] = CkLowVmInstruction.Jump(target = afterIf)
        instructions[secondBranchDoneJumpIndex] = CkLowVmInstruction.Jump(target = afterIf)
        instructions += CkLowVmInstruction.I32Add(dst = 2, lhs = 2, rhs = 3)
        instructions += CkLowVmInstruction.Jump(target = loopStart)
        instructions[exitJumpIndex] = CkLowVmInstruction.JumpIfFalse(cond = 0, target = instructions.size)
        instructions += CkLowVmInstruction.Return(CkLowVmRegister.I32(1))

        return CkLowVmImage(
            languageVersion = "ckl-low-1",
            memorySize = 1024u,
            entryFunctionIndex = 0,
            functions =
                listOf(
                    CkLowVmFunction(
                        name = "main",
                        i32RegisterCount = 18,
                        i64RegisterCount = 0,
                        addrRegisterCount = 0,
                        boolRegisterCount = 1,
                        parameters = emptyList(),
                        instructions = instructions,
                    ),
                ),
        )
    }

    private fun functionMixImage(iterations: Int): CkLowVmImage =
        CkLowVmImage(
            languageVersion = "ckl-low-1",
            memorySize = 1024u,
            entryFunctionIndex = 0,
            functions =
                listOf(
                    CkLowVmFunction(
                        name = "main",
                        i32RegisterCount = 5,
                        i64RegisterCount = 0,
                        addrRegisterCount = 0,
                        boolRegisterCount = 1,
                        parameters = emptyList(),
                        instructions = functionMixMainInstructions(iterations),
                    ),
                    CkLowVmFunction(
                        name = "mixA",
                        i32RegisterCount = 11,
                        i64RegisterCount = 0,
                        addrRegisterCount = 0,
                        boolRegisterCount = 0,
                        parameters = listOf(CkLowVmRegister.I32(0), CkLowVmRegister.I32(1)),
                        instructions =
                            listOf(
                                CkLowVmInstruction.I32Const(dst = 2, value = 17),
                                CkLowVmInstruction.I32Const(dst = 3, value = 3),
                                CkLowVmInstruction.I32Const(dst = 4, value = 1),
                                CkLowVmInstruction.I32Mul(dst = 5, lhs = 1, rhs = 2),
                                CkLowVmInstruction.I32Add(dst = 6, lhs = 0, rhs = 5),
                                CkLowVmInstruction.I32Shl(dst = 7, lhs = 0, rhs = 3),
                                CkLowVmInstruction.I32BitXor(dst = 8, lhs = 6, rhs = 7),
                                CkLowVmInstruction.I32Shr(dst = 9, lhs = 1, rhs = 4),
                                CkLowVmInstruction.I32Add(dst = 10, lhs = 8, rhs = 9),
                                CkLowVmInstruction.Return(CkLowVmRegister.I32(10)),
                            ),
                    ),
                    CkLowVmFunction(
                        name = "mixB",
                        i32RegisterCount = 11,
                        i64RegisterCount = 0,
                        addrRegisterCount = 0,
                        boolRegisterCount = 0,
                        parameters = listOf(CkLowVmRegister.I32(0), CkLowVmRegister.I32(1)),
                        instructions =
                            listOf(
                                CkLowVmInstruction.I32Const(dst = 2, value = 131),
                                CkLowVmInstruction.I32Const(dst = 3, value = 2),
                                CkLowVmInstruction.I32Const(dst = 4, value = 4),
                                CkLowVmInstruction.I32Mul(dst = 5, lhs = 1, rhs = 2),
                                CkLowVmInstruction.I32BitXor(dst = 6, lhs = 0, rhs = 5),
                                CkLowVmInstruction.I32Shr(dst = 7, lhs = 0, rhs = 3),
                                CkLowVmInstruction.I32Add(dst = 8, lhs = 6, rhs = 7),
                                CkLowVmInstruction.I32Shl(dst = 9, lhs = 1, rhs = 4),
                                CkLowVmInstruction.I32BitXor(dst = 10, lhs = 8, rhs = 9),
                                CkLowVmInstruction.Return(CkLowVmRegister.I32(10)),
                            ),
                    ),
                ),
        )

    private fun functionMixMainInstructions(iterations: Int): List<CkLowVmInstruction> {
        val instructions = mutableListOf<CkLowVmInstruction>()
        instructions += CkLowVmInstruction.I32Const(dst = 0, value = iterations)
        instructions += CkLowVmInstruction.I32Const(dst = 1, value = 324_508_639)
        instructions += CkLowVmInstruction.I32Const(dst = 2, value = 0)
        instructions += CkLowVmInstruction.I32Const(dst = 3, value = 1)
        val loopStart = instructions.size
        instructions += CkLowVmInstruction.I32Lt(dst = 0, lhs = 2, rhs = 0)
        val exitJumpIndex = instructions.size
        instructions += CkLowVmInstruction.JumpIfFalse(cond = 0, target = -1)
        instructions +=
            CkLowVmInstruction.CallStatic(
                returnRegister = CkLowVmRegister.I32(4),
                functionIndex = 1,
                arguments = listOf(CkLowVmRegister.I32(1), CkLowVmRegister.I32(2)),
            )
        instructions +=
            CkLowVmInstruction.CallStatic(
                returnRegister = CkLowVmRegister.I32(1),
                functionIndex = 2,
                arguments = listOf(CkLowVmRegister.I32(4), CkLowVmRegister.I32(2)),
            )
        instructions += CkLowVmInstruction.I32Add(dst = 2, lhs = 2, rhs = 3)
        instructions += CkLowVmInstruction.Jump(target = loopStart)
        instructions[exitJumpIndex] = CkLowVmInstruction.JumpIfFalse(cond = 0, target = instructions.size)
        instructions += CkLowVmInstruction.Return(CkLowVmRegister.I32(1))
        return instructions
    }

    private fun recursiveFibImage(iterations: Int): CkLowVmImage =
        CkLowVmImage(
            languageVersion = "ckl-low-1",
            memorySize = 1024u,
            entryFunctionIndex = 0,
            functions =
                listOf(
                    CkLowVmFunction(
                        name = "main",
                        i32RegisterCount = 12,
                        i64RegisterCount = 0,
                        addrRegisterCount = 0,
                        boolRegisterCount = 1,
                        parameters = emptyList(),
                        instructions = recursiveFibMainInstructions(iterations),
                    ),
                    CkLowVmFunction(
                        name = "fib",
                        i32RegisterCount = 7,
                        i64RegisterCount = 0,
                        addrRegisterCount = 0,
                        boolRegisterCount = 1,
                        parameters = listOf(CkLowVmRegister.I32(0)),
                        instructions = fibFunctionInstructions(),
                    ),
                ),
        )

    private fun recursiveFibMainInstructions(iterations: Int): List<CkLowVmInstruction> {
        val instructions = mutableListOf<CkLowVmInstruction>()
        instructions += CkLowVmInstruction.I32Const(dst = 0, value = iterations)
        instructions += CkLowVmInstruction.I32Const(dst = 1, value = 0)
        instructions += CkLowVmInstruction.I32Const(dst = 2, value = 0)
        instructions += CkLowVmInstruction.I32Const(dst = 3, value = 1)
        instructions += CkLowVmInstruction.I32Const(dst = 4, value = 6)
        instructions += CkLowVmInstruction.I32Const(dst = 5, value = 10)
        instructions += CkLowVmInstruction.I32Const(dst = 6, value = 31)
        val loopStart = instructions.size
        instructions += CkLowVmInstruction.I32Lt(dst = 0, lhs = 2, rhs = 0)
        val exitJumpIndex = instructions.size
        instructions += CkLowVmInstruction.JumpIfFalse(cond = 0, target = -1)
        instructions += CkLowVmInstruction.I32Div(dst = 7, lhs = 2, rhs = 4)
        instructions += CkLowVmInstruction.I32Mul(dst = 8, lhs = 7, rhs = 4)
        instructions += CkLowVmInstruction.I32Sub(dst = 9, lhs = 2, rhs = 8)
        instructions += CkLowVmInstruction.I32Add(dst = 9, lhs = 9, rhs = 5)
        instructions +=
            CkLowVmInstruction.CallStatic(
                returnRegister = CkLowVmRegister.I32(10),
                functionIndex = 1,
                arguments = listOf(CkLowVmRegister.I32(9)),
            )
        instructions += CkLowVmInstruction.I32Mul(dst = 11, lhs = 2, rhs = 6)
        instructions += CkLowVmInstruction.I32BitXor(dst = 10, lhs = 10, rhs = 11)
        instructions += CkLowVmInstruction.I32Add(dst = 1, lhs = 1, rhs = 10)
        instructions += CkLowVmInstruction.I32Add(dst = 2, lhs = 2, rhs = 3)
        instructions += CkLowVmInstruction.Jump(target = loopStart)
        instructions[exitJumpIndex] = CkLowVmInstruction.JumpIfFalse(cond = 0, target = instructions.size)
        instructions += CkLowVmInstruction.Return(CkLowVmRegister.I32(1))
        return instructions
    }

    private fun fibFunctionInstructions(): List<CkLowVmInstruction> =
        listOf(
            CkLowVmInstruction.I32Const(dst = 1, value = 2),
            CkLowVmInstruction.I32Lt(dst = 0, lhs = 0, rhs = 1),
            CkLowVmInstruction.JumpIfFalse(cond = 0, target = 4),
            CkLowVmInstruction.Return(CkLowVmRegister.I32(0)),
            CkLowVmInstruction.I32Const(dst = 2, value = 1),
            CkLowVmInstruction.I32Sub(dst = 3, lhs = 0, rhs = 2),
            CkLowVmInstruction.CallStatic(
                returnRegister = CkLowVmRegister.I32(4),
                functionIndex = 1,
                arguments = listOf(CkLowVmRegister.I32(3)),
            ),
            CkLowVmInstruction.I32Const(dst = 2, value = 2),
            CkLowVmInstruction.I32Sub(dst = 3, lhs = 0, rhs = 2),
            CkLowVmInstruction.CallStatic(
                returnRegister = CkLowVmRegister.I32(5),
                functionIndex = 1,
                arguments = listOf(CkLowVmRegister.I32(3)),
            ),
            CkLowVmInstruction.I32Add(dst = 6, lhs = 4, rhs = 5),
            CkLowVmInstruction.Return(CkLowVmRegister.I32(6)),
        )

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
        }
    }
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
