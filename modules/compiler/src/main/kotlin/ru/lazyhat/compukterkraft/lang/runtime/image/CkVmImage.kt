package ru.lazyhat.compukterkraft.lang.runtime.image

data class CkVmImage(
    val languageVersion: String,
    val targetAbiVersion: Int,
    val capabilities: List<String> = emptyList(),
    val constants: List<CkVmConstant> = emptyList(),
    val hostImports: List<CkVmHostImport> = emptyList(),
    val entryFunctionIndex: Int,
    val functions: List<CkVmFunction>,
)

sealed interface CkVmConstant {
    data class StringConstant(
        val value: String,
    ) : CkVmConstant

    data class IntConstant(
        val value: Int,
    ) : CkVmConstant

    data class LongConstant(
        val value: Long,
    ) : CkVmConstant
}

data class CkVmHostImport(
    val id: Int,
    val moduleName: String,
    val functionName: String,
    val parameterTypes: List<String>,
    val returnType: String,
)

data class CkVmFunction(
    val name: String,
    val frameSize: Int,
    val code: List<Int>,
)

object CkVmImageOpcodes {
    const val PUSH_UNIT = 1
    const val RETURN = 2
    const val PUSH_CONSTANT = 3
    const val CALL_HOST = 4
    const val POP = 5
    const val PUSH_BOOL = 6
    const val PUSH_NULL = 7
    const val LOAD_LOCAL = 8
    const val STORE_LOCAL = 9
    const val JUMP = 10
    const val JUMP_IF_FALSE = 11
    const val JUMP_IF_TRUE = 12
    const val BINARY = 13
    const val UNARY = 14
    const val CALL_FUNCTION = 15
    const val CONSTRUCT_RECORD = 16
    const val GET_FIELD = 17
}