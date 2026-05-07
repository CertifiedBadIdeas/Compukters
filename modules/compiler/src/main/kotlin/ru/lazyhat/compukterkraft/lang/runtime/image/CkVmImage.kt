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