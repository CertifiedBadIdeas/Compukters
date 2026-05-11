package ru.lazyhat.compukterkraft.lang.runtime.image

data class CkVmImage(
    val languageVersion: String,
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
    val registerCount: Int,
    val parameterCount: Int,
    val instructions: List<CkVmInstruction>,
    val i32RegisterCount: Int = registerCount,
    val i64RegisterCount: Int = 0,
    val boolRegisterCount: Int = 0,
    val refRegisterCount: Int = 0,
    val parameters: List<CkVmTypedRegister> =
        List(parameterCount) { index ->
            CkVmTypedRegister.Ref(index)
        },
)

sealed interface CkVmTypedRegister {
    val index: Int

    data class I32(
        override val index: Int,
    ) : CkVmTypedRegister

    data class I64(
        override val index: Int,
    ) : CkVmTypedRegister

    data class Bool(
        override val index: Int,
    ) : CkVmTypedRegister

    data class Ref(
        override val index: Int,
    ) : CkVmTypedRegister
}

sealed interface CkVmInstruction {
    data class LoadConst(
        val dst: Int,
        val constantIndex: Int,
    ) : CkVmInstruction

    data class LoadUnit(
        val dst: Int,
    ) : CkVmInstruction

    data class LoadNull(
        val dst: Int,
    ) : CkVmInstruction

    data class LoadBool(
        val dst: Int,
        val value: Boolean,
    ) : CkVmInstruction

    data class Move(
        val dst: Int,
        val src: Int,
    ) : CkVmInstruction

    data class I32Add(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : CkVmInstruction

    data class I32Sub(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : CkVmInstruction

    data class I32Mul(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : CkVmInstruction

    data class I32Div(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : CkVmInstruction

    data class I32Neg(
        val dst: Int,
        val src: Int,
    ) : CkVmInstruction

    data class I32BitAnd(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : CkVmInstruction

    data class I32BitOr(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : CkVmInstruction

    data class I32BitXor(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : CkVmInstruction

    data class I32BitNot(
        val dst: Int,
        val src: Int,
    ) : CkVmInstruction

    data class I32Shl(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : CkVmInstruction

    data class I32Shr(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : CkVmInstruction

    data class I32Eq(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : CkVmInstruction

    data class I32Ne(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : CkVmInstruction

    data class I32Lt(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : CkVmInstruction

    data class I32Le(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : CkVmInstruction

    data class I32Gt(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : CkVmInstruction

    data class I32Ge(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : CkVmInstruction

    data class BoolNot(
        val dst: Int,
        val src: Int,
    ) : CkVmInstruction

    data class BoolAnd(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : CkVmInstruction

    data class BoolOr(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : CkVmInstruction

    data class Jump(
        val target: Int,
    ) : CkVmInstruction

    data class JumpIfFalse(
        val cond: Int,
        val target: Int,
    ) : CkVmInstruction

    data class JumpIfTrue(
        val cond: Int,
        val target: Int,
    ) : CkVmInstruction

    data class CallStatic(
        val returnRegister: Int?,
        val functionIndex: Int,
        val arguments: List<Int>,
    ) : CkVmInstruction

    data class Return(
        val src: Int,
    ) : CkVmInstruction

    data object ReturnUnit : CkVmInstruction

    data class CallHost(
        val returnRegister: Int?,
        val importId: Int,
        val arguments: List<Int>,
    ) : CkVmInstruction

    data class Yield(
        val dst: Int,
    ) : CkVmInstruction

    data class Sleep(
        val dst: Int,
        val ticks: Int,
    ) : CkVmInstruction

    data class ConstructRecord(
        val dst: Int,
        val typeNameConstantIndex: Int,
        val fieldNameConstantIndices: List<Int>,
        val fieldValues: List<Int>,
    ) : CkVmInstruction

    data class GetField(
        val dst: Int,
        val receiver: Int,
        val fieldNameConstantIndex: Int,
    ) : CkVmInstruction
}
