package ru.lazyhat.compukterkraft.lang.runtime.image.low

data class RuxLowVmImage(
    val memorySize: UInt,
    val rodata: ByteArray = byteArrayOf(),
    val data: ByteArray = byteArrayOf(),
    val bssSize: UInt = 0u,
    val entryFunctionIndex: Int,
    val functions: List<RuxLowVmFunction>,
) {
    override fun equals(other: Any?): Boolean =
        other is RuxLowVmImage &&
            memorySize == other.memorySize &&
            rodata.contentEquals(other.rodata) &&
            data.contentEquals(other.data) &&
            bssSize == other.bssSize &&
            entryFunctionIndex == other.entryFunctionIndex &&
            functions == other.functions

    override fun hashCode(): Int {
        var result = memorySize.hashCode()
        result = 31 * result + rodata.contentHashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + bssSize.hashCode()
        result = 31 * result + entryFunctionIndex
        result = 31 * result + functions.hashCode()
        return result
    }
}

data class RuxLowVmFunction(
    val name: String,
    val registerCount: Int,
    val parameters: List<Int>,
    val instructions: List<RuxLowVmInstruction>,
)

sealed interface RuxLowVmInstruction {
    data class I32Const(
        val dst: Int,
        val value: Int,
    ) : RuxLowVmInstruction

    data class I64Const(
        val dst: Int,
        val value: Long,
    ) : RuxLowVmInstruction

    data class AddrConst(
        val dst: Int,
        val value: UInt,
    ) : RuxLowVmInstruction

    data class I32Move(
        val dst: Int,
        val src: Int,
    ) : RuxLowVmInstruction

    data class AddrMove(
        val dst: Int,
        val src: Int,
    ) : RuxLowVmInstruction

    data class I32Add(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : RuxLowVmInstruction

    data class I32Sub(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : RuxLowVmInstruction

    data class I32Mul(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : RuxLowVmInstruction

    data class I32Div(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : RuxLowVmInstruction

    data class I32Rem(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : RuxLowVmInstruction

    data class U32Div(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : RuxLowVmInstruction

    data class U32Rem(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : RuxLowVmInstruction

    data class I32BitXor(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : RuxLowVmInstruction

    data class I32Shl(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : RuxLowVmInstruction

    data class I32Shr(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : RuxLowVmInstruction

    data class I32Lt(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : RuxLowVmInstruction

    data class I32Eq(
        val dst: Int,
        val lhs: Int,
        val rhs: Int,
    ) : RuxLowVmInstruction

    data class Load32(
        val dst: Int,
        val addr: Int,
    ) : RuxLowVmInstruction

    data class Store32(
        val addr: Int,
        val src: Int,
    ) : RuxLowVmInstruction

    data class AddrAdd(
        val dst: Int,
        val base: Int,
        val offset: Int,
    ) : RuxLowVmInstruction

    data class Jump(
        val target: Int,
    ) : RuxLowVmInstruction

    data class JumpIfFalse(
        val cond: Int,
        val target: Int,
    ) : RuxLowVmInstruction

    data class CallStatic(
        val returnRegister: Int?,
        val functionIndex: Int,
        val arguments: List<Int>,
    ) : RuxLowVmInstruction

    data class ReturnI32(
        val src: Int,
    ) : RuxLowVmInstruction

    data class ReturnI64(
        val src: Int,
    ) : RuxLowVmInstruction

    data class ReturnAddr(
        val src: Int,
    ) : RuxLowVmInstruction

    data class ReturnBool(
        val src: Int,
    ) : RuxLowVmInstruction

    data object ReturnUnit : RuxLowVmInstruction
}
