package ru.lazyhat.compukterkraft.lang.runtime.image.low

data class CkLowVmImage(
    val languageVersion: String,
    val memorySize: UInt,
    val rodata: ByteArray = byteArrayOf(),
    val data: ByteArray = byteArrayOf(),
    val bssSize: UInt = 0u,
    val entryFunctionIndex: Int,
    val functions: List<CkLowVmFunction>,
) {
    override fun equals(other: Any?): Boolean =
        other is CkLowVmImage &&
            languageVersion == other.languageVersion &&
            memorySize == other.memorySize &&
            rodata.contentEquals(other.rodata) &&
            data.contentEquals(other.data) &&
            bssSize == other.bssSize &&
            entryFunctionIndex == other.entryFunctionIndex &&
            functions == other.functions

    override fun hashCode(): Int {
        var result = languageVersion.hashCode()
        result = 31 * result + memorySize.hashCode()
        result = 31 * result + rodata.contentHashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + bssSize.hashCode()
        result = 31 * result + entryFunctionIndex
        result = 31 * result + functions.hashCode()
        return result
    }
}

data class CkLowVmFunction(
    val name: String,
    val registerCount: Int,
    val parameters: List<Int>,
    val instructions: List<CkLowVmInstruction>,
)

sealed interface CkLowVmInstruction {
    data class I32Const(
        val dst: Int,
        val value: Int,
    ) : CkLowVmInstruction

    data class I64Const(
        val dst: Int,
        val value: Long,
    ) : CkLowVmInstruction

    data class AddrConst(
        val dst: Int,
        val value: UInt,
    ) : CkLowVmInstruction

    data class I32Move(
        val dst: Int,
        val src: Int,
    ) : CkLowVmInstruction

    data class AddrMove(
        val dst: Int,
        val src: Int,
    ) : CkLowVmInstruction

    data class I32Add(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : CkLowVmInstruction

    data class I32Sub(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : CkLowVmInstruction

    data class I32Mul(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : CkLowVmInstruction

    data class I32Div(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : CkLowVmInstruction

    data class I32BitXor(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : CkLowVmInstruction

    data class I32Shl(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : CkLowVmInstruction

    data class I32Shr(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : CkLowVmInstruction

    data class I32Lt(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : CkLowVmInstruction

    data class I32Eq(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : CkLowVmInstruction

    data class Load32(
        val dst: Int,
        val addr: Int,
    ) : CkLowVmInstruction

    data class Store32(
        val addr: Int,
        val src: Int,
    ) : CkLowVmInstruction

    data class AddrAdd(
        val dst: Int,
        val base: Int,
        val offset: Int,
    ) : CkLowVmInstruction

    data class Jump(
        val target: Int,
    ) : CkLowVmInstruction

    data class JumpIfFalse(
        val cond: Int,
        val target: Int,
    ) : CkLowVmInstruction

    data class CallStatic(
        val returnRegister: Int?,
        val functionIndex: Int,
        val arguments: List<Int>,
    ) : CkLowVmInstruction

    data class ReturnI32(
        val src: Int,
    ) : CkLowVmInstruction

    data class ReturnI64(
        val src: Int,
    ) : CkLowVmInstruction

    data class ReturnAddr(
        val src: Int,
    ) : CkLowVmInstruction

    data class ReturnBool(
        val src: Int,
    ) : CkLowVmInstruction

    data object ReturnUnit : CkLowVmInstruction
}
