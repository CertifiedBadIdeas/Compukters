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

package ru.lazyhat.compukterkraft.lang.runtime.abi

import ru.lazyhat.compukterkraft.lang.api.BinaryOperator
import ru.lazyhat.compukterkraft.lang.api.BytecodeClass
import ru.lazyhat.compukterkraft.lang.api.BytecodeFunction
import ru.lazyhat.compukterkraft.lang.api.BytecodeModule
import ru.lazyhat.compukterkraft.lang.api.BytecodeRecord
import ru.lazyhat.compukterkraft.lang.api.Instruction
import ru.lazyhat.compukterkraft.lang.api.UnaryOperator
import java.io.ByteArrayOutputStream

object BytecodeAbi {
    const val VERSION: Int = 1

    object Tags {
        const val PUSH_INT = 1
        const val PUSH_LONG = 2
        const val PUSH_STRING = 3
        const val PUSH_BOOL = 4
        const val PUSH_UNIT = 5
        const val PUSH_NULL = 6
        const val LOAD_LOCAL = 7
        const val STORE_LOCAL = 8
        const val POP = 9
        const val JUMP = 10
        const val JUMP_IF_FALSE = 11
        const val JUMP_IF_TRUE = 12
        const val CALL_FUNCTION = 13
        const val CALL_BUILTIN = 14
        const val GET_FIELD = 15
        const val SET_FIELD = 16
        const val CONSTRUCT_RECORD = 17
        const val CONSTRUCT_CLASS = 18
        const val CONSTRUCT_ARRAY = 19
        const val CONSTRUCT_LIST = 20
        const val CONSTRUCT_MAP = 21
        const val INDEX_GET = 22
        const val INDEX_SET = 23
        const val CALL_COLLECTION_METHOD = 24
        const val CALL_METHOD = 25
        const val CALL_STATIC_METHOD = 26
        const val BINARY = 27
        const val UNARY = 28
        const val RETURN = 29
    }

    fun encode(module: BytecodeModule): ByteArray {
        val out = Writer()
        out.bytes(byteArrayOf('C'.code.toByte(), 'K'.code.toByte(), 'V'.code.toByte(), 'M'.code.toByte()))
        out.u8(VERSION)
        out.string(module.name)
        out.i32(module.entryFunctionIndex)
        out.list(module.records, out::record)
        out.list(module.classes, out::klass)
        out.list(module.functions, out::function)
        return out.toByteArray()
    }

    private class Writer {
        private val out = ByteArrayOutputStream()

        fun toByteArray(): ByteArray = out.toByteArray()

        fun bytes(value: ByteArray) = out.write(value)

        fun u8(value: Int) = out.write(value and 0xff)

        fun bool(value: Boolean) = u8(if (value) 1 else 0)

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

        fun <T> list(
            values: List<T>,
            write: (T) -> Unit,
        ) {
            i32(values.size)
            values.forEach(write)
        }

        fun record(record: BytecodeRecord) {
            string(record.name)
            list(record.fields) { field ->
                string(field.name)
                string(field.typeName)
            }
        }

        fun klass(klass: BytecodeClass) {
            string(klass.name)
            list(klass.fields) { field ->
                string(field.name)
                string(field.typeName)
                bool(field.mutable)
            }
            i32(klass.initFunctionIndex ?: -1)
            list(klass.instanceMethods.entries.sortedBy { it.key }) { entry ->
                string(entry.key)
                i32(entry.value)
            }
            list(klass.staticMethods.entries.sortedBy { it.key }) { entry ->
                string(entry.key)
                i32(entry.value)
            }
        }

        fun function(function: BytecodeFunction) {
            string(function.name)
            list(function.parameters) { local ->
                string(local.name)
                string(local.typeName)
            }
            list(function.locals) { local ->
                string(local.name)
                string(local.typeName)
            }
            string(function.returnType)
            list(function.instructions, ::instruction)
        }

        fun instruction(instruction: Instruction) {
            when (instruction) {
                is Instruction.PushInt -> {
                    u8(Tags.PUSH_INT)
                    i32(instruction.value)
                }

                is Instruction.PushLong -> {
                    u8(Tags.PUSH_LONG)
                    i64(instruction.value)
                }

                is Instruction.PushString -> {
                    u8(Tags.PUSH_STRING)
                    string(instruction.value)
                }

                is Instruction.PushBool -> {
                    u8(Tags.PUSH_BOOL)
                    bool(instruction.value)
                }

                Instruction.PushUnit -> u8(Tags.PUSH_UNIT)
                Instruction.PushNull -> u8(Tags.PUSH_NULL)

                is Instruction.LoadLocal -> {
                    u8(Tags.LOAD_LOCAL)
                    i32(instruction.slot)
                }

                is Instruction.StoreLocal -> {
                    u8(Tags.STORE_LOCAL)
                    i32(instruction.slot)
                }

                Instruction.Pop -> u8(Tags.POP)

                is Instruction.Jump -> {
                    u8(Tags.JUMP)
                    i32(instruction.target)
                }

                is Instruction.JumpIfFalse -> {
                    u8(Tags.JUMP_IF_FALSE)
                    i32(instruction.target)
                }

                is Instruction.JumpIfTrue -> {
                    u8(Tags.JUMP_IF_TRUE)
                    i32(instruction.target)
                }

                is Instruction.CallFunction -> {
                    u8(Tags.CALL_FUNCTION)
                    i32(instruction.functionIndex)
                    i32(instruction.argumentCount)
                }

                is Instruction.CallBuiltin -> {
                    u8(Tags.CALL_BUILTIN)
                    string(instruction.moduleName.orEmpty())
                    string(instruction.functionName)
                    i32(instruction.argumentCount)
                }

                is Instruction.GetField -> {
                    u8(Tags.GET_FIELD)
                    string(instruction.fieldName)
                }

                is Instruction.SetField -> {
                    u8(Tags.SET_FIELD)
                    string(instruction.fieldName)
                }

                is Instruction.ConstructRecord -> {
                    u8(Tags.CONSTRUCT_RECORD)
                    string(instruction.typeName)
                    list(instruction.fieldNames, ::string)
                }

                is Instruction.ConstructClass -> {
                    u8(Tags.CONSTRUCT_CLASS)
                    string(instruction.className)
                    list(instruction.fieldNames, ::string)
                }

                Instruction.ConstructArray -> u8(Tags.CONSTRUCT_ARRAY)

                is Instruction.ConstructList -> {
                    u8(Tags.CONSTRUCT_LIST)
                    i32(instruction.elementCount)
                }

                is Instruction.ConstructMap -> {
                    u8(Tags.CONSTRUCT_MAP)
                    i32(instruction.entryCount)
                }

                Instruction.IndexGet -> u8(Tags.INDEX_GET)
                Instruction.IndexSet -> u8(Tags.INDEX_SET)

                is Instruction.CallCollectionMethod -> {
                    u8(Tags.CALL_COLLECTION_METHOD)
                    string(instruction.methodName)
                    i32(instruction.argumentCount)
                }

                is Instruction.CallMethod -> {
                    u8(Tags.CALL_METHOD)
                    string(instruction.methodName)
                    i32(instruction.argumentCount)
                }

                is Instruction.CallStaticMethod -> {
                    u8(Tags.CALL_STATIC_METHOD)
                    string(instruction.className)
                    string(instruction.methodName)
                    i32(instruction.argumentCount)
                }

                is Instruction.Binary -> {
                    u8(Tags.BINARY)
                    u8(instruction.operator.abiTag())
                }

                is Instruction.Unary -> {
                    u8(Tags.UNARY)
                    u8(instruction.operator.abiTag())
                }

                Instruction.Return -> u8(Tags.RETURN)
            }
        }
    }
}

private fun BinaryOperator.abiTag(): Int = ordinal

private fun UnaryOperator.abiTag(): Int = ordinal
