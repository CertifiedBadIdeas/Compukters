package ru.lazyhat.compukterkraft.lang.runtime.image

import java.io.ByteArrayOutputStream

object CkVmImageAbi {
    const val VERSION: Int = 1

    object ConstantTags {
        const val STRING = 1
        const val INT = 2
        const val LONG = 3
    }

    fun encode(image: CkVmImage): ByteArray {
        validate(image)
        val out = Writer()
        out.bytes(byteArrayOf('C'.code.toByte(), 'K'.code.toByte(), 'I'.code.toByte(), 'M'.code.toByte()))
        out.u8(VERSION)
        out.string(image.languageVersion)
        out.u16(image.targetAbiVersion)
        out.list(image.capabilities, out::string)
        out.list(image.constants, out::constant)
        out.list(image.hostImports, out::hostImport)
        out.i32(image.entryFunctionIndex)
        out.list(image.functions, out::function)
        return out.toByteArray()
    }

    private fun validate(image: CkVmImage) {
        require(image.targetAbiVersion in 0..0xffff) { "Target ABI version must fit u16." }
        require(image.functions.isNotEmpty()) { "Image must contain at least one function." }
        require(image.entryFunctionIndex in image.functions.indices) { "Entry function index is outside the function table." }
        image.hostImports.forEach { hostImport ->
            require(hostImport.id >= 0) { "Host import id must be non-negative." }
        }
        image.functions.forEach { function ->
            require(function.frameSize >= 0) { "Function frame size must be non-negative." }
            function.code.forEach { byte ->
                require(byte in 0..0xff) { "Code byte must be in 0..255." }
            }
        }
    }

    private class Writer {
        private val out = ByteArrayOutputStream()

        fun toByteArray(): ByteArray = out.toByteArray()

        fun bytes(value: ByteArray) = out.write(value)

        fun u8(value: Int) = out.write(value and 0xff)

        fun u16(value: Int) {
            u8(value)
            u8(value ushr 8)
        }

        fun i32(value: Int) {
            u8(value)
            u8(value ushr 8)
            u8(value ushr 16)
            u8(value ushr 24)
        }

        fun i64(value: Long) {
            repeat(8) { index -> u8((value ushr (index * 8)).toInt()) }
        }

        fun string(value: String) {
            val bytes = value.encodeToByteArray()
            i32(bytes.size)
            bytes(bytes)
        }

        fun byteArray(value: List<Int>) {
            i32(value.size)
            value.forEach(::u8)
        }

        fun <T> list(
            values: List<T>,
            write: (T) -> Unit,
        ) {
            i32(values.size)
            values.forEach(write)
        }

        fun constant(constant: CkVmConstant) {
            when (constant) {
                is CkVmConstant.StringConstant -> {
                    u8(ConstantTags.STRING)
                    string(constant.value)
                }
                is CkVmConstant.IntConstant -> {
                    u8(ConstantTags.INT)
                    i32(constant.value)
                }
                is CkVmConstant.LongConstant -> {
                    u8(ConstantTags.LONG)
                    i64(constant.value)
                }
            }
        }

        fun hostImport(hostImport: CkVmHostImport) {
            i32(hostImport.id)
            string(hostImport.moduleName)
            string(hostImport.functionName)
            list(hostImport.parameterTypes, ::string)
            string(hostImport.returnType)
        }

        fun function(function: CkVmFunction) {
            string(function.name)
            i32(function.frameSize)
            byteArray(function.code)
        }
    }
}