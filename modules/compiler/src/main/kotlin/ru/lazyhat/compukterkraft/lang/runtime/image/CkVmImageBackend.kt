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
            targetAbiVersion = CkVmImageAbi.VERSION,
            capabilities = if (hostImports.isEmpty()) emptyList() else listOf("host-import-ids"),
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
            .map { instruction -> CkVmHostImportRegistry.require(requireNotNull(instruction.moduleName), instruction.functionName, instruction.argumentCount) }
            .distinct()
            .sortedBy { import -> import.id }
            .toList()

    private class LoweringContext(
        hostImports: List<CkVmHostImport>,
    ) {
        private val hostImportIds = hostImports.associateBy { Triple(it.moduleName, it.functionName, it.parameterTypes.size) }
        val constants = mutableListOf<CkVmConstant>()

        fun lower(function: BytecodeFunction): CkVmFunction {
            val offsets = instructionOffsets(function.instructions)
            val code =
                function.instructions.flatMapIndexed { index, instruction ->
                    lowerInstruction(instruction, offsets, function.instructions.size, index)
                }
            return CkVmFunction(
                name = function.name,
                frameSize = function.parameters.size + function.locals.size,
                code = code,
            )
        }

        private fun instructionOffsets(instructions: List<Instruction>): List<Int> {
            val offsets = mutableListOf<Int>()
            var offset = 0
            instructions.forEach { instruction ->
                offsets += offset
                offset += instructionLength(instruction)
            }
            offsets += offset
            return offsets
        }

        private fun instructionLength(instruction: Instruction): Int =
            when (instruction) {
                Instruction.PushUnit,
                Instruction.PushNull,
                Instruction.Return,
                Instruction.Pop,
                -> 1
                is Instruction.PushBool -> 2
                is Instruction.PushString,
                is Instruction.PushInt,
                is Instruction.PushLong,
                is Instruction.LoadLocal,
                is Instruction.StoreLocal,
                is Instruction.Jump,
                is Instruction.JumpIfFalse,
                is Instruction.JumpIfTrue,
                -> 5
                is Instruction.Binary,
                is Instruction.Unary,
                -> 2
                is Instruction.CallBuiltin -> 9
                else -> throw UnsupportedOperationException("CkVmImage backend does not support ${instruction::class.simpleName}")
            }

        private fun lowerInstruction(
            instruction: Instruction,
            offsets: List<Int>,
            instructionCount: Int,
            instructionIndex: Int,
        ): List<Int> =
            when (instruction) {
                Instruction.PushUnit -> listOf(CkVmImageOpcodes.PUSH_UNIT)
                Instruction.PushNull -> listOf(CkVmImageOpcodes.PUSH_NULL)
                Instruction.Return -> listOf(CkVmImageOpcodes.RETURN)
                Instruction.Pop -> listOf(CkVmImageOpcodes.POP)
                is Instruction.PushBool -> listOf(CkVmImageOpcodes.PUSH_BOOL, if (instruction.value) 1 else 0)
                is Instruction.PushString -> pushConstant(CkVmConstant.StringConstant(instruction.value))
                is Instruction.PushInt -> pushConstant(CkVmConstant.IntConstant(instruction.value))
                is Instruction.PushLong -> pushConstant(CkVmConstant.LongConstant(instruction.value))
                is Instruction.LoadLocal -> listOf(CkVmImageOpcodes.LOAD_LOCAL) + i32(instruction.slot)
                is Instruction.StoreLocal -> listOf(CkVmImageOpcodes.STORE_LOCAL) + i32(instruction.slot)
                is Instruction.Jump -> listOf(CkVmImageOpcodes.JUMP) + i32(resolveJumpTarget(instruction.target, offsets, instructionCount, instructionIndex))
                is Instruction.JumpIfFalse -> listOf(CkVmImageOpcodes.JUMP_IF_FALSE) + i32(resolveJumpTarget(instruction.target, offsets, instructionCount, instructionIndex))
                is Instruction.JumpIfTrue -> listOf(CkVmImageOpcodes.JUMP_IF_TRUE) + i32(resolveJumpTarget(instruction.target, offsets, instructionCount, instructionIndex))
                is Instruction.Binary -> listOf(CkVmImageOpcodes.BINARY, binaryOperatorTag(instruction.operator))
                is Instruction.Unary -> listOf(CkVmImageOpcodes.UNARY, unaryOperatorTag(instruction.operator))
                is Instruction.CallBuiltin -> callBuiltin(instruction)
                else -> throw UnsupportedOperationException("CkVmImage backend does not support ${instruction::class.simpleName}")
            }

        private fun resolveJumpTarget(
            target: Int,
            offsets: List<Int>,
            instructionCount: Int,
            instructionIndex: Int,
        ): Int {
            require(target in 0..instructionCount) {
                "CkVmImage jump target $target at instruction $instructionIndex is outside 0..$instructionCount"
            }
            return offsets[target]
        }

        private fun pushConstant(constant: CkVmConstant): List<Int> {
            val existing = constants.indexOf(constant)
            val index = if (existing >= 0) existing else constants.size.also { constants += constant }
            return listOf(CkVmImageOpcodes.PUSH_CONSTANT) + i32(index)
        }

        private fun callBuiltin(instruction: Instruction.CallBuiltin): List<Int> {
            val moduleName = instruction.moduleName
                ?: throw UnsupportedOperationException("CkVmImage backend does not support global builtin ${instruction.functionName}")
            val import = hostImportIds.getValue(Triple(moduleName, instruction.functionName, instruction.argumentCount))
            return listOf(CkVmImageOpcodes.CALL_HOST) + i32(import.id) + i32(instruction.argumentCount)
        }

        private fun binaryOperatorTag(operator: BinaryOperator): Int =
            when (operator) {
                BinaryOperator.ADD -> 0
                BinaryOperator.SUBTRACT -> 1
                BinaryOperator.MULTIPLY -> 2
                BinaryOperator.DIVIDE -> 3
                BinaryOperator.EQUALS -> 4
                BinaryOperator.NOT_EQUALS -> 5
                BinaryOperator.LESS -> 6
                BinaryOperator.LESS_EQUALS -> 7
                BinaryOperator.GREATER -> 8
                BinaryOperator.GREATER_EQUALS -> 9
                BinaryOperator.AND -> 10
                BinaryOperator.OR -> 11
                BinaryOperator.BIT_AND -> 12
                BinaryOperator.BIT_OR -> 13
                BinaryOperator.BIT_XOR -> 14
                BinaryOperator.SHIFT_LEFT -> 15
                BinaryOperator.SHIFT_RIGHT -> 16
            }

        private fun unaryOperatorTag(operator: UnaryOperator): Int =
            when (operator) {
                UnaryOperator.NEGATE -> 0
                UnaryOperator.NOT -> 1
                UnaryOperator.BIT_NOT -> 2
            }

        private fun i32(value: Int): List<Int> =
            listOf(value and 0xff, (value ushr 8) and 0xff, (value ushr 16) and 0xff, (value ushr 24) and 0xff)
    }
}