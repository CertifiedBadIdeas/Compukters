/*
 * The Compukters Developers
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

package ru.lazyhat.compukters.lang.runtime

/**
 * Kinds of VM signals reported by the native runtime. Used by profiling collectors.
 */
enum class VmSignalKind {
    HALT,
    PAUSE,
    YIELD,
    SLEEP,
    WAIT_EVENT,
    WAIT_POLL,
    WAIT_PROCESS,
    HOST_CALL,
}

/**
 * Kinds of VM instructions reported by profiling collectors.
 */
enum class VmInstructionKind {
    PUSH_INT,
    PUSH_LONG,
    PUSH_STRING,
    PUSH_BOOL,
    PUSH_UNIT,
    PUSH_NULL,
    LOAD_LOCAL,
    STORE_LOCAL,
    POP,
    JUMP,
    JUMP_IF_FALSE,
    JUMP_IF_TRUE,
    CALL_FUNCTION,
    CALL_BUILTIN,
    GET_FIELD,
    SET_FIELD,
    CONSTRUCT_RECORD,
    CONSTRUCT_CLASS,
    CONSTRUCT_ARRAY,
    CONSTRUCT_LIST,
    CONSTRUCT_MAP,
    INDEX_GET,
    INDEX_SET,
    CALL_COLLECTION_METHOD,
    CALL_METHOD,
    CALL_STATIC_METHOD,
    BINARY,
    UNARY,
    RETURN,
}

interface DeviceRuntimeMetrics {
    val collectsDetailedMetrics: Boolean
        get() = true

    fun recordVmSignal(kind: VmSignalKind)

    fun recordVmHostCall(
        moduleName: String,
        functionName: String,
        nanos: Long,
    )

    fun recordVmHostCallWait(
        moduleName: String,
        functionName: String,
        nanos: Long,
    )

    fun recordVmInstruction(
        kind: VmInstructionKind,
        nanos: Long,
    )

    fun recordNativeWait(
        kind: String,
        nanos: Long,
        woke: Boolean,
    ) = Unit
}

object NoopDeviceRuntimeMetrics : DeviceRuntimeMetrics {
    override val collectsDetailedMetrics: Boolean = false

    override fun recordVmSignal(kind: VmSignalKind) = Unit

    override fun recordVmHostCall(
        moduleName: String,
        functionName: String,
        nanos: Long,
    ) = Unit

    override fun recordVmHostCallWait(
        moduleName: String,
        functionName: String,
        nanos: Long,
    ) = Unit

    override fun recordVmInstruction(
        kind: VmInstructionKind,
        nanos: Long,
    ) = Unit
}
