package ru.lazyhat.compukterkraft.lang.runtime.image

import ru.lazyhat.compukterkraft.lang.api.BytecodeFunction
import ru.lazyhat.compukterkraft.lang.api.BytecodeModule
import ru.lazyhat.compukterkraft.lang.api.Instruction
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

        fun lower(function: BytecodeFunction): CkVmFunction =
            CkVmFunction(
                name = function.name,
                frameSize = function.parameters.size + function.locals.size,
                code = function.instructions.flatMap(::lowerInstruction),
            )

        private fun lowerInstruction(instruction: Instruction): List<Int> =
            when (instruction) {
                Instruction.PushUnit -> listOf(CkVmImageOpcodes.PUSH_UNIT)
                Instruction.Return -> listOf(CkVmImageOpcodes.RETURN)
                Instruction.Pop -> listOf(CkVmImageOpcodes.POP)
                is Instruction.PushString -> pushConstant(CkVmConstant.StringConstant(instruction.value))
                is Instruction.PushInt -> pushConstant(CkVmConstant.IntConstant(instruction.value))
                is Instruction.PushLong -> pushConstant(CkVmConstant.LongConstant(instruction.value))
                is Instruction.CallBuiltin -> callBuiltin(instruction)
                else -> throw UnsupportedOperationException("CkVmImage backend does not support ${instruction::class.simpleName}")
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

        private fun i32(value: Int): List<Int> =
            listOf(value and 0xff, (value ushr 8) and 0xff, (value ushr 16) and 0xff, (value ushr 24) and 0xff)
    }
}