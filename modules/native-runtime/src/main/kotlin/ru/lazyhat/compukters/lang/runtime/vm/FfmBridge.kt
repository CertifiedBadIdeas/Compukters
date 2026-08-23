/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ru.lazyhat.compukters.lang.runtime.vm

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.Path

internal class FfmBridge private constructor(
    private val arena: Arena,
    private val abiVersionHandle: MethodHandle,
    private val maximumCreateBytesHandle: MethodHandle,
    private val maximumOutcomeBytesHandle: MethodHandle,
    private val createHandle: MethodHandle,
    private val advanceHandle: MethodHandle,
    private val resumeUnitHandle: MethodHandle,
    private val resumeStringHandle: MethodHandle,
    private val resumeFailureHandle: MethodHandle,
    private val closeHandle: MethodHandle,
) : LowLevelVmBridge,
    AutoCloseable {
    fun abiVersion(): Int = abiVersionHandle.invokeExact() as Int

    override fun create(artifact: ByteArray): ByteArray =
        Arena.ofConfined().use { callArena ->
            val maximum = maximumCreateBytes()
            val output = callArena.allocate(maximum.toLong())
            val written = callArena.allocate(ValueLayout.JAVA_LONG)
            val status =
                createHandle.invokeExact(
                    callArena.nativeBytes(artifact),
                    artifact.size.toLong(),
                    output,
                    maximum.toLong(),
                    written,
                ) as Int
            requireSuccess("create", status)
            copyResult("create", output, written, maximum)
        }

    override fun advance(
        handle: Long,
        guestBudget: Int,
        maintenanceBudget: Int,
    ): ByteArray =
        Arena.ofConfined().use { callArena ->
            val maximum = maximumOutcomeBytes()
            val output = callArena.allocate(maximum.toLong())
            val written = callArena.allocate(ValueLayout.JAVA_LONG)
            val status =
                advanceHandle.invokeExact(
                    handle,
                    guestBudget,
                    maintenanceBudget,
                    output,
                    maximum.toLong(),
                    written,
                ) as Int
            requireSuccess("advance", status)
            copyResult("advance", output, written, maximum)
        }

    override fun resumeUnit(
        handle: Long,
        requestId: Long,
    ) = requireSuccess("resume unit", resumeUnitHandle.invokeExact(handle, requestId) as Int)

    override fun resumeString(
        handle: Long,
        requestId: Long,
        value: CharArray,
    ) {
        Arena.ofConfined().use { callArena ->
            requireSuccess(
                "resume string",
                resumeStringHandle.invokeExact(
                    handle,
                    requestId,
                    callArena.nativeChars(value),
                    value.size.toLong(),
                ) as Int,
            )
        }
    }

    override fun resumeFailure(
        handle: Long,
        requestId: Long,
        kind: Int,
        code: Long,
    ) =
        requireSuccess(
            "resume failure",
            resumeFailureHandle.invokeExact(handle, requestId, kind, code.toInt()) as Int,
        )

    override fun close(handle: Long) = requireSuccess("close", closeHandle.invokeExact(handle) as Int)

    override fun close() = arena.close()

    private fun maximumOutcomeBytes(): Int {
        val value = maximumOutcomeBytesHandle.invokeExact() as Long
        if (value !in 1..MAXIMUM_OUTCOME_BYTES) throw VmBridgeException("invalid maximum FFM outcome size")
        return value.toInt()
    }

    private fun maximumCreateBytes(): Int {
        val value = maximumCreateBytesHandle.invokeExact() as Long
        if (value !in 1..MAXIMUM_CREATE_BYTES) throw VmBridgeException("invalid maximum FFM create size")
        return value.toInt()
    }

    private fun copyResult(
        operation: String,
        output: MemorySegment,
        written: MemorySegment,
        maximum: Int,
    ): ByteArray {
        val length = written.get(ValueLayout.JAVA_LONG, 0)
        if (length !in 1..maximum.toLong()) throw VmBridgeException("invalid FFM $operation length")
        return output.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE)
    }

    private fun requireSuccess(
        operation: String,
        status: Int,
    ) {
        if (status != STATUS_OK) throw failure(operation, status)
    }

    private fun failure(
        operation: String,
        status: Int,
    ): VmBridgeException = VmBridgeException("FFM $operation failed with status $status")

    private fun Arena.nativeBytes(value: ByteArray): MemorySegment {
        if (value.isEmpty()) return MemorySegment.NULL
        return allocate(ValueLayout.JAVA_BYTE, value.size.toLong()).also { destination ->
            MemorySegment.copy(value, 0, destination, ValueLayout.JAVA_BYTE, 0, value.size)
        }
    }

    private fun Arena.nativeChars(value: CharArray): MemorySegment {
        if (value.isEmpty()) return MemorySegment.NULL
        return allocate(ValueLayout.JAVA_CHAR, value.size.toLong()).also { destination ->
            MemorySegment.copy(value, 0, destination, ValueLayout.JAVA_CHAR, 0, value.size)
        }
    }

    companion object {
        private const val STATUS_OK = 0
        private const val MAXIMUM_CREATE_BYTES = 1024
        private const val MAXIMUM_OUTCOME_BYTES = 1024 * 1024

        fun open(library: Path): FfmBridge {
            val arena = Arena.ofShared()
            try {
                val lookup = SymbolLookup.libraryLookup(library, arena)
                val linker = Linker.nativeLinker()
                fun downcall(
                    name: String,
                    descriptor: FunctionDescriptor,
                ): MethodHandle = linker.downcallHandle(lookup.find(name).orElseThrow(), descriptor)

                return FfmBridge(
                    arena = arena,
                    abiVersionHandle = downcall("compukter_abi_version", FunctionDescriptor.of(ValueLayout.JAVA_INT)),
                    maximumCreateBytesHandle =
                        downcall("compukter_max_create_bytes", FunctionDescriptor.of(ValueLayout.JAVA_LONG)),
                    maximumOutcomeBytesHandle =
                        downcall("compukter_max_outcome_bytes", FunctionDescriptor.of(ValueLayout.JAVA_LONG)),
                    createHandle =
                        downcall(
                            "compukter_create",
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                            ),
                        ),
                    advanceHandle =
                        downcall(
                            "compukter_advance",
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                            ),
                        ),
                    resumeUnitHandle =
                        downcall(
                            "compukter_resume_unit",
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),
                        ),
                    resumeStringHandle =
                        downcall(
                            "compukter_resume_string",
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                            ),
                        ),
                    resumeFailureHandle =
                        downcall(
                            "compukter_resume_failure",
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT,
                            ),
                        ),
                    closeHandle =
                        downcall("compukter_close", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)),
                ).also { bridge ->
                    if (bridge.abiVersion() != 1) throw VmBridgeException("unsupported Compukter FFM ABI")
                }
            } catch (error: Throwable) {
                arena.close()
                throw error
            }
        }

    }
}
