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

package ru.lazyhat.compukterkraft.lang.runtime.image

import ru.lazyhat.compukterkraft.lang.api.BinaryOperator
import ru.lazyhat.compukterkraft.lang.api.BytecodeFunction
import ru.lazyhat.compukterkraft.lang.api.BytecodeModule
import ru.lazyhat.compukterkraft.lang.api.Instruction
import ru.lazyhat.compukterkraft.lang.api.UnaryOperator
import ru.lazyhat.compukterkraft.lang.frontend.CompilationArtifact
import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import ru.lazyhat.compukterkraft.lang.frontend.NoOpSourceLoader
import ru.lazyhat.compukterkraft.lang.frontend.SourceLoader

data class CkVmImageCompilationArtifact(
    val image: CkVmImage?,
    val bytecode: CompilationArtifact,
)

fun LanguageFrontend.compileImage(
    name: String,
    source: String,
): CkVmImageCompilationArtifact = compileImage(name, source, NoOpSourceLoader)

fun LanguageFrontend.compileImage(
    name: String,
    source: String,
    loader: SourceLoader,
): CkVmImageCompilationArtifact {
    val bytecode = compile(name, source, loader)
    return CkVmImageCompilationArtifact(
        image = bytecode.module?.let(CkVmImageCompiler::compile),
        bytecode = bytecode,
    )
}

object CkVmImageCompiler {
    fun compile(module: BytecodeModule): CkVmImage {
        val hostImports = collectHostImports(module)
        val context = LoweringContext(hostImports)
        val functions = module.functions.map { function -> context.lower(function) }
        return CkVmImage(
            languageVersion = "ckl-1",
            constants = context.constants,
            hostImports = hostImports,
            entryFunctionIndex = module.entryFunctionIndex,
            functions = functions,
        )
    }

    private fun collectHostImports(module: BytecodeModule): List<CkVmHostImport> =
        module.functions
            .asSequence()
            .flatMap { function -> function.instructions.filterIsInstance<Instruction.CallBuiltin>() }
            .filter { instruction -> instruction.moduleName != null }
            .map { instruction ->
                CkVmHostImportRegistry.require(
                    requireNotNull(instruction.moduleName),
                    instruction.functionName,
                    instruction.argumentCount,
                )
            }.distinct()
            .sortedBy { import -> import.id }
            .toList()

    private sealed interface PendingInstruction {
        data class Resolved(
            val instruction: CkVmInstruction,
        ) : PendingInstruction

        data class Jump(
            val targetBytecodeIndex: Int,
        ) : PendingInstruction

        data class JumpIfFalse(
            val cond: Int,
            val targetBytecodeIndex: Int,
        ) : PendingInstruction

        data class JumpIfTrue(
            val cond: Int,
            val targetBytecodeIndex: Int,
        ) : PendingInstruction
    }

    private class LoweringContext(
        hostImports: List<CkVmHostImport>,
    ) {
        private val hostImportIds = hostImports.associateBy { Triple(it.moduleName, it.functionName, it.parameterTypes.size) }
        val constants = mutableListOf<CkVmConstant>()

        fun lower(function: BytecodeFunction): CkVmFunction {
            val lowerer = FunctionLowerer(function)
            return lowerer.lower()
        }

        private inner class FunctionLowerer(
            private val function: BytecodeFunction,
        ) {
            private val stack = ArrayDeque<Int>()
            private val pending = mutableListOf<PendingInstruction>()
            private val bytecodeToInstruction = MutableList(function.instructions.size + 1) { 0 }
            private var nextRegister = function.locals.size

            fun lower(): CkVmFunction {
                function.instructions.forEachIndexed { index, instruction ->
                    bytecodeToInstruction[index] = pending.size
                    lowerInstruction(instruction)
                }
                bytecodeToInstruction[function.instructions.size] = pending.size

                val instructions =
                    pending.mapIndexed { index, instruction ->
                        resolveInstruction(index, instruction)
                    }
                return CkVmFunction(
                    name = function.name,
                    registerCount = nextRegister,
                    parameterCount = function.parameters.size,
                    instructions = instructions,
                )
            }

            private fun lowerInstruction(instruction: Instruction) {
                when (instruction) {
                    Instruction.PushUnit -> {
                        val dst = temp()
                        emit(CkVmInstruction.LoadUnit(dst))
                        stack.addLast(dst)
                    }

                    Instruction.PushNull -> {
                        val dst = temp()
                        emit(CkVmInstruction.LoadNull(dst))
                        stack.addLast(dst)
                    }

                    is Instruction.PushBool -> {
                        val dst = temp()
                        emit(CkVmInstruction.LoadBool(dst, instruction.value))
                        stack.addLast(dst)
                    }

                    is Instruction.PushString -> pushConstant(CkVmConstant.StringConstant(instruction.value))
                    is Instruction.PushInt -> pushConstant(CkVmConstant.IntConstant(instruction.value))
                    is Instruction.PushLong -> pushConstant(CkVmConstant.LongConstant(instruction.value))
                    is Instruction.LoadLocal -> stack.addLast(instruction.slot)
                    is Instruction.StoreLocal -> emit(CkVmInstruction.Move(instruction.slot, pop("store local")))
                    Instruction.Pop -> pop("pop")
                    is Instruction.Jump -> pending += PendingInstruction.Jump(instruction.target)
                    is Instruction.JumpIfFalse -> pending += PendingInstruction.JumpIfFalse(pop("jump-if-false"), instruction.target)
                    is Instruction.JumpIfTrue -> pending += PendingInstruction.JumpIfTrue(pop("jump-if-true"), instruction.target)
                    is Instruction.Binary -> lowerBinary(instruction.operator)
                    is Instruction.Unary -> lowerUnary(instruction.operator)
                    is Instruction.CallFunction -> lowerCallFunction(instruction)
                    is Instruction.CallBuiltin -> lowerCallBuiltin(instruction)
                    Instruction.Return -> lowerReturn()
                    else -> throw UnsupportedOperationException(
                        "CkVmImage register backend does not support ${instruction::class.simpleName}",
                    )
                }
            }

            private fun pushConstant(constant: CkVmConstant) {
                val dst = temp()
                emit(CkVmInstruction.LoadConst(dst, constantIndex(constant)))
                stack.addLast(dst)
            }

            private fun lowerBinary(operator: BinaryOperator) {
                val rhs = pop("binary rhs")
                val lhs = pop("binary lhs")
                val dst = temp()
                emit(
                    when (operator) {
                        BinaryOperator.ADD -> CkVmInstruction.I32Add(dst, lhs, rhs)
                        BinaryOperator.SUBTRACT -> CkVmInstruction.I32Sub(dst, lhs, rhs)
                        BinaryOperator.MULTIPLY -> CkVmInstruction.I32Mul(dst, lhs, rhs)
                        BinaryOperator.DIVIDE -> CkVmInstruction.I32Div(dst, lhs, rhs)
                        BinaryOperator.EQUALS -> CkVmInstruction.I32Eq(dst, lhs, rhs)
                        BinaryOperator.NOT_EQUALS -> CkVmInstruction.I32Ne(dst, lhs, rhs)
                        BinaryOperator.LESS -> CkVmInstruction.I32Lt(dst, lhs, rhs)
                        BinaryOperator.LESS_EQUALS -> CkVmInstruction.I32Le(dst, lhs, rhs)
                        BinaryOperator.GREATER -> CkVmInstruction.I32Gt(dst, lhs, rhs)
                        BinaryOperator.GREATER_EQUALS -> CkVmInstruction.I32Ge(dst, lhs, rhs)
                        BinaryOperator.AND -> CkVmInstruction.BoolAnd(dst, lhs, rhs)
                        BinaryOperator.OR -> CkVmInstruction.BoolOr(dst, lhs, rhs)
                        BinaryOperator.BIT_AND -> CkVmInstruction.I32BitAnd(dst, lhs, rhs)
                        BinaryOperator.BIT_OR -> CkVmInstruction.I32BitOr(dst, lhs, rhs)
                        BinaryOperator.BIT_XOR -> CkVmInstruction.I32BitXor(dst, lhs, rhs)
                        BinaryOperator.SHIFT_LEFT -> CkVmInstruction.I32Shl(dst, lhs, rhs)
                        BinaryOperator.SHIFT_RIGHT -> CkVmInstruction.I32Shr(dst, lhs, rhs)
                    },
                )
                stack.addLast(dst)
            }

            private fun lowerUnary(operator: UnaryOperator) {
                val src = pop("unary operand")
                val dst = temp()
                emit(
                    when (operator) {
                        UnaryOperator.NEGATE -> CkVmInstruction.I32Neg(dst, src)
                        UnaryOperator.NOT -> CkVmInstruction.BoolNot(dst, src)
                        UnaryOperator.BIT_NOT -> CkVmInstruction.I32BitNot(dst, src)
                    },
                )
                stack.addLast(dst)
            }

            private fun lowerCallFunction(instruction: Instruction.CallFunction) {
                val arguments = popArguments(instruction.argumentCount)
                val dst = temp()
                emit(CkVmInstruction.CallStatic(dst, instruction.functionIndex, arguments))
                stack.addLast(dst)
            }

            private fun lowerCallBuiltin(instruction: Instruction.CallBuiltin) {
                if (instruction.moduleName == null) {
                    lowerGlobalBuiltin(instruction)
                    return
                }

                val arguments = popArguments(instruction.argumentCount)
                val import = hostImportIds.getValue(Triple(instruction.moduleName, instruction.functionName, instruction.argumentCount))
                val dst = temp()
                emit(CkVmInstruction.CallHost(dst, import.id, arguments))
                stack.addLast(dst)
            }

            private fun lowerGlobalBuiltin(instruction: Instruction.CallBuiltin) {
                when {
                    instruction.functionName == "yield" && instruction.argumentCount == 0 -> {
                        val dst = temp()
                        emit(CkVmInstruction.Yield(dst))
                        stack.addLast(dst)
                    }

                    instruction.functionName == "sleep" && instruction.argumentCount == 1 -> {
                        val ticks = pop("sleep ticks")
                        val dst = temp()
                        emit(CkVmInstruction.Sleep(dst, ticks))
                        stack.addLast(dst)
                    }

                    else -> throw UnsupportedOperationException(
                        "CkVmImage register backend does not support global builtin ${instruction.functionName}",
                    )
                }
            }

            private fun lowerReturn() {
                val src = stack.removeLastOrNull()
                if (src == null) {
                    emit(CkVmInstruction.ReturnUnit)
                } else {
                    emit(CkVmInstruction.Return(src))
                }
            }

            private fun popArguments(argumentCount: Int): List<Int> =
                List(argumentCount) { pop("call argument") }.asReversed()

            private fun pop(context: String): Int =
                stack.removeLastOrNull()
                    ?: throw IllegalStateException("CkVmImage register backend stack underflow while lowering $context in ${function.name}.")

            private fun temp(): Int = nextRegister++

            private fun emit(instruction: CkVmInstruction) {
                pending += PendingInstruction.Resolved(instruction)
            }

            private fun resolveInstruction(
                index: Int,
                instruction: PendingInstruction,
            ): CkVmInstruction =
                when (instruction) {
                    is PendingInstruction.Resolved -> instruction.instruction
                    is PendingInstruction.Jump -> CkVmInstruction.Jump(resolveJumpTarget(index, instruction.targetBytecodeIndex))
                    is PendingInstruction.JumpIfFalse ->
                        CkVmInstruction.JumpIfFalse(instruction.cond, resolveJumpTarget(index, instruction.targetBytecodeIndex))

                    is PendingInstruction.JumpIfTrue ->
                        CkVmInstruction.JumpIfTrue(instruction.cond, resolveJumpTarget(index, instruction.targetBytecodeIndex))
                }

            private fun resolveJumpTarget(
                instructionIndex: Int,
                targetBytecodeIndex: Int,
            ): Int {
                require(targetBytecodeIndex in bytecodeToInstruction.indices) {
                    "CkVmImage jump target $targetBytecodeIndex at register instruction $instructionIndex is outside 0..${function.instructions.size}"
                }
                return bytecodeToInstruction[targetBytecodeIndex]
            }
        }

        private fun constantIndex(constant: CkVmConstant): Int {
            val existing = constants.indexOf(constant)
            return if (existing >= 0) existing else constants.size.also { constants += constant }
        }
    }
}
