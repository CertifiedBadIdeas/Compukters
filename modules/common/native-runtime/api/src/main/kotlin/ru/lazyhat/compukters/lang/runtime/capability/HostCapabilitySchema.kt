/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.lang.runtime.capability

import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity

enum class HostValueType(
    internal val wireCode: Int,
) {
    UNIT(0),
    I32(1),
    I64(2),
    F32(3),
    F64(4),
    BOOL(5),
    CHAR(6),
    STRING(7),
}

class HostOperationSchema(
    arguments: List<HostValueType>,
    val result: HostValueType,
    val asynchronous: Boolean,
) {
    val arguments: List<HostValueType> = arguments.toList()

    init {
        require(this.arguments.size <= HostCapabilityLimits.MAXIMUM_ARGUMENTS) {
            "host capability operation has too many arguments"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is HostOperationSchema &&
            arguments == other.arguments &&
            result == other.result &&
            asynchronous == other.asynchronous

    override fun hashCode(): Int = 31 * (31 * arguments.hashCode() + result.hashCode()) + asynchronous.hashCode()

    override fun toString(): String = "HostOperationSchema(arguments=$arguments, result=$result, asynchronous=$asynchronous)"
}

class HostCapabilitySchema(
    val identity: CapabilityIdentity,
    operations: List<HostOperationSchema>,
) {
    val operations: List<HostOperationSchema> = operations.toList()

    init {
        require(COMPONENT.matches(identity.namespace)) { "invalid host capability namespace: ${identity.namespace}" }
        require(COMPONENT.matches(identity.name)) { "invalid host capability name: ${identity.name}" }
        require(identity.abiMajor in 1..UShort.MAX_VALUE.toInt()) { "host capability ABI major is out of range" }
        require(identity.abiMinor in 0..UShort.MAX_VALUE.toInt()) { "host capability ABI minor is out of range" }
        require(this.operations.isNotEmpty()) { "host capability must define at least one operation" }
        require(this.operations.size <= HostCapabilityLimits.MAXIMUM_OPERATIONS) {
            "host capability has too many operations"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is HostCapabilitySchema && identity == other.identity && operations == other.operations

    override fun hashCode(): Int = 31 * identity.hashCode() + operations.hashCode()

    override fun toString(): String = "HostCapabilitySchema(identity=$identity, operations=$operations)"

    private companion object {
        val COMPONENT = Regex("[a-z][a-z0-9-]{0,63}")
    }
}

object HostCapabilityLimits {
    const val MAXIMUM_CAPABILITIES: Int = 28
    const val MAXIMUM_OPERATIONS: Int = 256
    const val MAXIMUM_ARGUMENTS: Int = 32
    const val MAXIMUM_WIRE_BYTES: Int = 64 * 1024
}

internal object HostCapabilitySchemaWire {
    private const val VERSION = 1

    fun encode(schemas: List<HostCapabilitySchema>): ByteArray {
        require(schemas.size <= HostCapabilityLimits.MAXIMUM_CAPABILITIES) { "too many host capabilities" }
        require(schemas.distinctBy { Triple(it.identity.namespace, it.identity.name, it.identity.abiMajor) }.size == schemas.size) {
            "duplicate host capability identity"
        }
        val sink = BoundedWireSink(HostCapabilityLimits.MAXIMUM_WIRE_BYTES)
        sink.u8(VERSION)
        sink.u8(schemas.size)
        schemas.forEach { schema ->
            sink.ascii(schema.identity.namespace)
            sink.ascii(schema.identity.name)
            sink.u16(schema.identity.abiMajor)
            sink.u16(schema.identity.abiMinor)
            sink.u16(schema.operations.size)
            schema.operations.forEach { operation ->
                sink.u8(if (operation.asynchronous) 1 else 0)
                sink.u8(operation.result.wireCode)
                sink.u8(operation.arguments.size)
                operation.arguments.forEach { argument -> sink.u8(argument.wireCode) }
            }
        }
        return sink.bytes()
    }
}

private class BoundedWireSink(
    capacity: Int,
) {
    private val buffer = ByteArray(capacity)
    private var position = 0

    fun u8(value: Int) {
        require(value in 0..UByte.MAX_VALUE.toInt()) { "wire u8 is out of range" }
        write(value)
    }

    fun u16(value: Int) {
        require(value in 0..UShort.MAX_VALUE.toInt()) { "wire u16 is out of range" }
        write(value)
        write(value ushr 8)
    }

    fun ascii(value: String) {
        val encoded = value.encodeToByteArray()
        require(encoded.size <= UByte.MAX_VALUE.toInt()) { "wire string is too long" }
        require(encoded.all { it >= 0 }) { "wire string must be ASCII" }
        u8(encoded.size)
        encoded.forEach { write(it.toInt()) }
    }

    fun bytes(): ByteArray = buffer.copyOf(position)

    private fun write(value: Int) {
        require(position < buffer.size) { "host capability schema exceeds wire limit" }
        buffer[position++] = value.toByte()
    }
}
