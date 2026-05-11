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
        val context = LoweringContext(module, hostImports)
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
        private val module: BytecodeModule,
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
            private var nextI32 = 0
            private var nextI64 = 0
            private var nextBool = 0
            private var nextRef = 0
            private val locals: List<CkVmTypedRegister> =
                function.locals.map { local ->
                    tempForType(local.typeName)
                }
            private val stack = ArrayDeque<CkVmTypedRegister>()
            private val pending = mutableListOf<PendingInstruction>()
            private val bytecodeToInstruction = MutableList(function.instructions.size + 1) { 0 }

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
                    i32RegisterCount = nextI32,
                    i64RegisterCount = nextI64,
                    boolRegisterCount = nextBool,
                    refRegisterCount = nextRef,
                    parameters = locals.take(function.parameters.size),
                    instructions = instructions,
                )
            }

            private fun lowerInstruction(instruction: Instruction) {
                when (instruction) {
                    Instruction.PushUnit -> {
                        val dst = tempRef()
                        emit(CkVmInstruction.LoadUnit(dst))
                        stack.addLast(CkVmTypedRegister.Ref(dst))
                    }

                    Instruction.PushNull -> {
                        val dst = tempRef()
                        emit(CkVmInstruction.LoadNull(dst))
                        stack.addLast(CkVmTypedRegister.Ref(dst))
                    }

                    is Instruction.PushBool -> {
                        val dst = tempBool()
                        emit(CkVmInstruction.BoolConst(dst, instruction.value))
                        stack.addLast(CkVmTypedRegister.Bool(dst))
                    }

                    is Instruction.PushString -> pushConstant(CkVmConstant.StringConstant(instruction.value))
                    is Instruction.PushInt -> pushConstant(CkVmConstant.IntConstant(instruction.value))
                    is Instruction.PushLong -> pushConstant(CkVmConstant.LongConstant(instruction.value))
                    is Instruction.LoadLocal -> stack.addLast(local(instruction.slot))
                    is Instruction.StoreLocal -> emitMove(local(instruction.slot), pop("store local"))
                    Instruction.Pop -> pop("pop")
                    is Instruction.Jump -> pending += PendingInstruction.Jump(instruction.target)
                    is Instruction.JumpIfFalse -> pending += PendingInstruction.JumpIfFalse(requireBool(pop("jump-if-false"), "jump-if-false"), instruction.target)
                    is Instruction.JumpIfTrue -> pending += PendingInstruction.JumpIfTrue(requireBool(pop("jump-if-true"), "jump-if-true"), instruction.target)
                    is Instruction.Binary -> lowerBinary(instruction.operator)
                    is Instruction.Unary -> lowerUnary(instruction.operator)
                    is Instruction.ConstructRecord -> lowerConstructRecord(instruction)
                    is Instruction.GetField -> lowerGetField(instruction)
                    is Instruction.CallFunction -> lowerCallFunction(instruction)
                    is Instruction.CallBuiltin -> lowerCallBuiltin(instruction)
                    Instruction.Return -> lowerReturn()
                    else -> throw UnsupportedOperationException(
                        "CkVmImage register backend does not support ${instruction::class.simpleName}",
                    )
                }
            }

            private fun pushConstant(constant: CkVmConstant) {
                val constantIndex = constantIndex(constant)
                when (constant) {
                    is CkVmConstant.IntConstant -> {
                        val dst = tempI32()
                        emit(CkVmInstruction.I32Const(dst, constantIndex))
                        stack.addLast(CkVmTypedRegister.I32(dst))
                    }

                    is CkVmConstant.LongConstant -> {
                        val dst = tempI64()
                        emit(CkVmInstruction.I64Const(dst, constantIndex))
                        stack.addLast(CkVmTypedRegister.I64(dst))
                    }

                    is CkVmConstant.StringConstant -> {
                        val dst = tempRef()
                        emit(CkVmInstruction.RefConst(dst, constantIndex))
                        stack.addLast(CkVmTypedRegister.Ref(dst))
                    }
                }
            }

            private fun lowerBinary(operator: BinaryOperator) {
                val rhs = pop("binary rhs")
                val lhs = pop("binary lhs")
                when (operator) {
                    BinaryOperator.ADD -> emitI32Binary(lhs, rhs, CkVmInstruction::I32Add)
                    BinaryOperator.SUBTRACT -> emitI32Binary(lhs, rhs, CkVmInstruction::I32Sub)
                    BinaryOperator.MULTIPLY -> emitI32Binary(lhs, rhs, CkVmInstruction::I32Mul)
                    BinaryOperator.DIVIDE -> emitI32Binary(lhs, rhs, CkVmInstruction::I32Div)
                    BinaryOperator.BIT_AND -> emitI32Binary(lhs, rhs, CkVmInstruction::I32BitAnd)
                    BinaryOperator.BIT_OR -> emitI32Binary(lhs, rhs, CkVmInstruction::I32BitOr)
                    BinaryOperator.BIT_XOR -> emitI32Binary(lhs, rhs, CkVmInstruction::I32BitXor)
                    BinaryOperator.SHIFT_LEFT -> emitI32Binary(lhs, rhs, CkVmInstruction::I32Shl)
                    BinaryOperator.SHIFT_RIGHT -> emitI32Binary(lhs, rhs, CkVmInstruction::I32Shr)
                    BinaryOperator.EQUALS -> emitI32Compare(lhs, rhs, CkVmInstruction::I32Eq)
                    BinaryOperator.NOT_EQUALS -> emitI32Compare(lhs, rhs, CkVmInstruction::I32Ne)
                    BinaryOperator.LESS -> emitI32Compare(lhs, rhs, CkVmInstruction::I32Lt)
                    BinaryOperator.LESS_EQUALS -> emitI32Compare(lhs, rhs, CkVmInstruction::I32Le)
                    BinaryOperator.GREATER -> emitI32Compare(lhs, rhs, CkVmInstruction::I32Gt)
                    BinaryOperator.GREATER_EQUALS -> emitI32Compare(lhs, rhs, CkVmInstruction::I32Ge)
                    BinaryOperator.AND -> emitBoolBinary(lhs, rhs, CkVmInstruction::BoolAnd)
                    BinaryOperator.OR -> emitBoolBinary(lhs, rhs, CkVmInstruction::BoolOr)
                }
            }

            private fun lowerUnary(operator: UnaryOperator) {
                val src = pop("unary operand")
                when (operator) {
                    UnaryOperator.NEGATE -> {
                        val dst = tempI32()
                        emit(CkVmInstruction.I32Neg(dst, requireI32(src, "unary minus")))
                        stack.addLast(CkVmTypedRegister.I32(dst))
                    }

                    UnaryOperator.NOT -> {
                        val dst = tempBool()
                        emit(CkVmInstruction.BoolNot(dst, requireBool(src, "boolean not")))
                        stack.addLast(CkVmTypedRegister.Bool(dst))
                    }

                    UnaryOperator.BIT_NOT -> {
                        val dst = tempI32()
                        emit(CkVmInstruction.I32BitNot(dst, requireI32(src, "bit not")))
                        stack.addLast(CkVmTypedRegister.I32(dst))
                    }
                }
            }

            private fun lowerConstructRecord(instruction: Instruction.ConstructRecord) {
                val values = popArguments(instruction.fieldNames.size)
                val dst = tempRef()
                emit(
                    CkVmInstruction.ConstructRecord(
                        dst = dst,
                        typeNameConstantIndex = constantIndex(CkVmConstant.StringConstant(instruction.typeName)),
                        fieldNameConstantIndices =
                            instruction.fieldNames.map { fieldName ->
                                constantIndex(CkVmConstant.StringConstant(fieldName))
                            },
                        fieldValues = values,
                    ),
                )
                stack.addLast(CkVmTypedRegister.Ref(dst))
            }

            private fun lowerGetField(instruction: Instruction.GetField) {
                val receiver = requireRef(pop("get field receiver"), "get field receiver")
                val dst = tempRef()
                emit(
                    CkVmInstruction.GetField(
                        dst = CkVmTypedRegister.Ref(dst),
                        receiver = receiver,
                        fieldNameConstantIndex = constantIndex(CkVmConstant.StringConstant(instruction.fieldName)),
                    ),
                )
                stack.addLast(CkVmTypedRegister.Ref(dst))
            }

            private fun lowerCallFunction(instruction: Instruction.CallFunction) {
                val arguments = popArguments(instruction.argumentCount)
                val dst = tempForType(module.functions[instruction.functionIndex].returnType)
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
                val dst = tempForType(import.returnType)
                emit(CkVmInstruction.CallHost(dst, import.id, arguments))
                stack.addLast(dst)
            }

            private fun lowerGlobalBuiltin(instruction: Instruction.CallBuiltin) {
                when {
                    instruction.functionName == "yield" && instruction.argumentCount == 0 -> {
                        val dst = tempRef()
                        emit(CkVmInstruction.Yield(dst))
                        stack.addLast(CkVmTypedRegister.Ref(dst))
                    }

                    instruction.functionName == "sleep" && instruction.argumentCount == 1 -> {
                        val ticks = pop("sleep ticks")
                        val dst = tempRef()
                        emit(CkVmInstruction.Sleep(dst, ticks))
                        stack.addLast(CkVmTypedRegister.Ref(dst))
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

            private fun popArguments(argumentCount: Int): List<CkVmTypedRegister> =
                List(argumentCount) { pop("call argument") }.asReversed()

            private fun pop(context: String): CkVmTypedRegister =
                stack.removeLastOrNull()
                    ?: throw IllegalStateException("CkVmImage register backend stack underflow while lowering $context in ${function.name}.")

            private fun local(slot: Int): CkVmTypedRegister =
                locals.getOrNull(slot)
                    ?: throw IllegalStateException("CkVmImage register backend local slot $slot is outside ${function.name}.")

            private fun tempForType(typeName: String): CkVmTypedRegister =
                when (typeName.removeSuffix("?")) {
                    "Int" -> CkVmTypedRegister.I32(tempI32())
                    "Long" -> CkVmTypedRegister.I64(tempI64())
                    "Bool" -> CkVmTypedRegister.Bool(tempBool())
                    else -> CkVmTypedRegister.Ref(tempRef())
                }

            private fun tempI32(): Int = nextI32++

            private fun tempI64(): Int = nextI64++

            private fun tempBool(): Int = nextBool++

            private fun tempRef(): Int = nextRef++

            private fun emitMove(
                dst: CkVmTypedRegister,
                src: CkVmTypedRegister,
            ) {
                when {
                    dst is CkVmTypedRegister.I32 && src is CkVmTypedRegister.I32 -> emit(CkVmInstruction.I32Move(dst.index, src.index))
                    dst is CkVmTypedRegister.I64 && src is CkVmTypedRegister.I64 -> emit(CkVmInstruction.I64Move(dst.index, src.index))
                    dst is CkVmTypedRegister.Bool && src is CkVmTypedRegister.Bool -> emit(CkVmInstruction.BoolMove(dst.index, src.index))
                    dst is CkVmTypedRegister.Ref && src is CkVmTypedRegister.Ref -> emit(CkVmInstruction.RefMove(dst.index, src.index))
                    else -> throw UnsupportedOperationException("CkVmImage register backend cannot move $src into $dst in ${function.name}.")
                }
            }

            private fun emitI32Binary(
                lhs: CkVmTypedRegister,
                rhs: CkVmTypedRegister,
                factory: (Int, Int, Int) -> CkVmInstruction,
            ) {
                val dst = tempI32()
                emit(factory(dst, requireI32(lhs, "i32 binary lhs"), requireI32(rhs, "i32 binary rhs")))
                stack.addLast(CkVmTypedRegister.I32(dst))
            }

            private fun emitI32Compare(
                lhs: CkVmTypedRegister,
                rhs: CkVmTypedRegister,
                factory: (Int, Int, Int) -> CkVmInstruction,
            ) {
                val dst = tempBool()
                emit(factory(dst, requireI32(lhs, "i32 comparison lhs"), requireI32(rhs, "i32 comparison rhs")))
                stack.addLast(CkVmTypedRegister.Bool(dst))
            }

            private fun emitBoolBinary(
                lhs: CkVmTypedRegister,
                rhs: CkVmTypedRegister,
                factory: (Int, Int, Int) -> CkVmInstruction,
            ) {
                val dst = tempBool()
                emit(factory(dst, requireBool(lhs, "bool binary lhs"), requireBool(rhs, "bool binary rhs")))
                stack.addLast(CkVmTypedRegister.Bool(dst))
            }

            private fun requireI32(
                register: CkVmTypedRegister,
                context: String,
            ): Int =
                (register as? CkVmTypedRegister.I32)?.index
                    ?: throw UnsupportedOperationException("CkVmImage register backend requires I32 for $context but found $register in ${function.name}.")

            private fun requireBool(
                register: CkVmTypedRegister,
                context: String,
            ): Int =
                (register as? CkVmTypedRegister.Bool)?.index
                    ?: throw UnsupportedOperationException("CkVmImage register backend requires Bool for $context but found $register in ${function.name}.")

            private fun requireRef(
                register: CkVmTypedRegister,
                context: String,
            ): Int =
                (register as? CkVmTypedRegister.Ref)?.index
                    ?: throw UnsupportedOperationException("CkVmImage register backend requires Ref for $context but found $register in ${function.name}.")

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
