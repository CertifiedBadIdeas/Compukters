/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.api.commands

import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.capability.HostValueType
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import java.io.ByteArrayOutputStream

/** Canonical compukters:commands ABI 2 payload. All text is length-prefixed little-endian UTF-16. */
object CommandBatchCodec {
    const val MAXIMUM_COMMANDS = 32
    const val MAXIMUM_BYTES = 4096
    const val MAXIMUM_FAILURE_UNITS = 48

    class Command(
        val symbol: String,
        arguments: List<VmValue>,
    ) {
        val arguments: List<VmValue> = arguments.toList()
    }

    fun decode(bytes: ByteArray): List<Command> {
        require(bytes.size in 2..MAXIMUM_BYTES) { "Invalid command packet size" }
        val input = Reader(bytes)
        require(input.byte() == 2) { "Unsupported command packet version" }
        val count = input.byte()
        require(count in 1..MAXIMUM_COMMANDS) { "Invalid command count" }
        val commands =
            List(count) {
                val symbol = input.text()
                require(symbol.length in 1..256 && symbol.all { it.isLetterOrDigit() || it in "_.$" }) { "Invalid command binding" }
                val arguments = input.byte()
                require(arguments <= 32) { "Too many command arguments" }
                Command(
                    symbol,
                    List(arguments) {
                        when (input.byte()) {
                            1 -> {
                                VmValue.I32(input.text().toInt())
                            }

                            2 -> {
                                VmValue.I64(input.text().toLong())
                            }

                            3 -> {
                                VmValue.F32(input.text().toFloat().toRawBits())
                            }

                            4 -> {
                                VmValue.F64(input.text().toDouble().toRawBits())
                            }

                            5 -> {
                                VmValue.Bool(
                                    when (input.text()) {
                                        "true" -> true
                                        "false" -> false
                                        else -> throw IllegalArgumentException("Invalid command Boolean")
                                    },
                                )
                            }

                            6 -> {
                                VmValue.CharValue(input.text().also { require(it.length == 1) }.single())
                            }

                            7 -> {
                                VmValue.StringValue(input.text())
                            }

                            8 -> {
                                VmValue.ByteArrayValue(input.bytes())
                            }

                            else -> {
                                throw IllegalArgumentException("Invalid command argument type")
                            }
                        }
                    },
                )
            }
        require(input.finished()) { "Trailing command packet bytes" }
        return commands
    }

    fun encode(commands: List<Command>): ByteArray {
        require(commands.size in 1..MAXIMUM_COMMANDS)
        val output = Writer()
        output.byte(2)
        output.byte(commands.size)
        commands.forEach { command ->
            output.text(command.symbol)
            require(command.arguments.size <= 32)
            output.byte(command.arguments.size)
            command.arguments.forEach { value ->
                when (value) {
                    is VmValue.I32 -> {
                        output.byte(1)
                        output.text(value.value.toString())
                    }

                    is VmValue.I64 -> {
                        output.byte(2)
                        output.text(value.value.toString())
                    }

                    is VmValue.F32 -> {
                        output.byte(3)
                        output.text(Float.fromBits(value.bits).toString())
                    }

                    is VmValue.F64 -> {
                        output.byte(4)
                        output.text(Double.fromBits(value.bits).toString())
                    }

                    is VmValue.Bool -> {
                        output.byte(5)
                        output.text(value.value.toString())
                    }

                    is VmValue.CharValue -> {
                        output.byte(6)
                        output.text(value.value.toString())
                    }

                    is VmValue.StringValue -> {
                        output.byte(7)
                        output.text(value.value)
                    }

                    is VmValue.ByteArrayValue -> {
                        output.byte(8)
                        output.bytes(value.value)
                    }

                    else -> {
                        throw IllegalArgumentException("Unsupported command argument")
                    }
                }
            }
        }
        return output.finish().also(::decode)
    }

    /** Reserve a bounded failure for every later position, so a large reply cannot lose the batch. */
    fun encodeResults(results: List<HostResponse>): ByteArray {
        require(results.size in 1..MAXIMUM_COMMANDS)
        val output = Writer()
        output.byte(2)
        output.byte(results.size)
        results.forEachIndexed { index, result ->
            val available = MAXIMUM_BYTES - output.size - (results.size - index - 1) * 101 - 2
            var encoded =
                try {
                    Writer().also { it.result(result) }.finish()
                } catch (_: IllegalArgumentException) {
                    byteArrayOf()
                }
            if (encoded.isEmpty() || encoded.size > available) {
                encoded =
                    Writer()
                        .also {
                            it.result(HostResponse.Failure(HostFailureKind.OTHER, "Command result exceeds byte limit"))
                        }.finish()
            }
            output.short(encoded.size)
            output.raw(encoded)
        }
        return output.finish()
    }

    fun typeOf(value: VmValue): HostValueType =
        when (value) {
            is VmValue.I32 -> HostValueType.I32
            is VmValue.I64 -> HostValueType.I64
            is VmValue.F32 -> HostValueType.F32
            is VmValue.F64 -> HostValueType.F64
            is VmValue.Bool -> HostValueType.BOOL
            is VmValue.CharValue -> HostValueType.CHAR
            is VmValue.StringValue -> HostValueType.STRING
            is VmValue.ByteArrayValue -> HostValueType.BYTE_ARRAY
            else -> throw IllegalArgumentException("Unsupported command argument")
        }

    private class Reader(
        private val input: ByteArray,
    ) {
        private var offset = 0

        fun byte(): Int {
            require(offset < input.size) { "Truncated command packet" }
            return input[offset++].toInt() and 255
        }

        fun short(): Int = byte() or (byte() shl 8)

        fun text(): String {
            val size = short()
            require(size <= (input.size - offset) / 2) { "Truncated command text" }
            return CharArray(size) { short().toChar() }.concatToString()
        }

        fun bytes(): ByteArray {
            val size = short()
            require(size <= input.size - offset) { "Truncated command bytes" }
            return input.copyOfRange(offset, offset + size).also { offset += size }
        }

        fun finished(): Boolean = offset == input.size
    }

    private class Writer {
        private val output = ByteArrayOutputStream()

        val size: Int get() = output.size()

        fun raw(bytes: ByteArray) {
            bytes.forEach { byte(it.toInt()) }
        }

        fun byte(value: Int) {
            require(output.size() < MAXIMUM_BYTES) { "Command packet exceeds byte limit" }
            output.write(value and 255)
        }

        fun short(value: Int) {
            require(value in 0..65535)
            byte(value)
            byte(value ushr 8)
        }

        fun text(value: String) {
            require(value.length <= MAXIMUM_BYTES / 2)
            short(value.length)
            value.forEach { short(it.code) }
        }

        fun bytes(value: ByteArray) {
            short(value.size)
            value.forEach { byte(it.toInt()) }
        }

        private fun int(value: Int) {
            repeat(4) { byte(value ushr (it * 8)) }
        }

        private fun long(value: Long) {
            repeat(8) { byte((value ushr (it * 8)).toInt()) }
        }

        fun result(result: HostResponse) {
            if (result is HostResponse.Failure) {
                byte(
                    when (result.kind) {
                        HostFailureKind.END_OF_FILE -> 1
                        HostFailureKind.UNAVAILABLE -> 2
                        HostFailureKind.INPUT_OUTPUT -> 3
                        HostFailureKind.CANCELLED -> 4
                        HostFailureKind.OTHER -> 5
                    },
                )
                var end = minOf(result.detail.length, MAXIMUM_FAILURE_UNITS)
                if (end < result.detail.length && end > 0 && result.detail[end - 1].isHighSurrogate()) end--
                text(result.detail.substring(0, end))
            } else {
                byte(0)
                writeValue(result, 0)
            }
        }

        private fun writeValue(
            value: HostResponse,
            depth: Int,
        ) {
            require(depth <= 8) { "Command record nesting exceeds limit" }
            when (value) {
                HostResponse.UnitSuccess -> {
                    byte(0)
                }

                is HostResponse.IntSuccess -> {
                    byte(1)
                    int(value.value)
                }

                is HostResponse.LongSuccess -> {
                    byte(2)
                    long(value.value)
                }

                is HostResponse.FloatSuccess -> {
                    byte(3)
                    int(value.value.toRawBits())
                }

                is HostResponse.DoubleSuccess -> {
                    byte(4)
                    long(value.value.toRawBits())
                }

                is HostResponse.BoolSuccess -> {
                    byte(5)
                    byte(if (value.value) 1 else 0)
                }

                is HostResponse.CharSuccess -> {
                    byte(6)
                    short(value.value.code)
                }

                is HostResponse.StringSuccess -> {
                    byte(7)
                    text(value.value)
                }

                is HostResponse.ByteArraySuccess -> {
                    byte(8)
                    bytes(value.value)
                }

                is HostResponse.RecordSuccess -> {
                    byte(9)
                    short(value.value.values.size)
                    value.value.values.forEach { writeValue(it, depth + 1) }
                }

                is HostResponse.Failure -> {
                    error("Failures cannot be nested values")
                }
            }
        }

        fun finish(): ByteArray = output.toByteArray()
    }
}
