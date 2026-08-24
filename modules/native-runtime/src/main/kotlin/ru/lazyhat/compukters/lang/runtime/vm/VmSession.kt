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

import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

class VmSession private constructor(
    handle: Long,
    private val bridge: LowLevelVmBridge,
    private val terminalTransport: TerminalWireTransport,
) : AutoCloseable {
    private val handle = AtomicLong(handle)

    fun advance(
        guestBudget: Int,
        maintenanceBudget: Int,
    ): VmOutcome {
        require(guestBudget >= 0 && maintenanceBudget >= 0) { "VM budgets must be non-negative" }
        return decodeNative { WireDecoder(bridge.advance(requireHandle(), guestBudget, maintenanceBudget)).outcome() }
    }

    fun resume(
        requestId: Long,
        response: HostResponse,
    ) {
        when (response) {
            HostResponse.UnitSuccess -> resumeUnit(requestId)
            is HostResponse.StringSuccess -> resumeString(requestId, response.value)
            is HostResponse.Failure -> resumeFailure(requestId, response.kind, response.code)
        }
    }

    fun resumeUnit(requestId: Long) = bridge.resumeUnit(requireHandle(), requestId)

    fun resumeString(
        requestId: Long,
        value: String,
    ) = bridge.resumeString(requireHandle(), requestId, value.toCharArray())

    fun resumeFailure(
        requestId: Long,
        kind: HostFailureKind,
        code: Long,
    ) {
        require(code in 0..UInt.MAX_VALUE.toLong()) { "host failure code must fit u32" }
        bridge.resumeFailure(requireHandle(), requestId, kind.wireCode, code)
    }

    fun commitTerminal(): Unit = bridge.terminalCommit(requireHandle())

    fun terminalFullState(): TerminalState = decodeNative { terminalTransport.fullState(requireHandle()) }

    fun terminalChangesSince(revision: Long): TerminalUpdate {
        require(revision >= 0) { "terminal revision must not be negative" }
        return decodeNative { terminalTransport.changesSince(requireHandle(), revision) }
    }

    fun sendTerminalKey(
        key: TerminalKey,
        action: TerminalKeyAction,
        modifiers: Set<TerminalModifier> = emptySet(),
    ): Unit =
        bridge.terminalKey(
            requireHandle(),
            key.wireCode,
            action.wireCode,
            modifiers.fold(0) { bits, modifier -> bits or modifier.mask },
        )

    fun sendTerminalText(value: String): Unit = bridge.terminalText(requireHandle(), value.codePoints().toArray())

    fun filesystemGeneration(): Long = decodeNative { GenerationWireDecoder(bridge.filesystemGeneration(requireHandle())).generation() }

    override fun close() {
        val closing = handle.getAndSet(CLOSED)
        if (closing != CLOSED) {
            try {
                terminalTransport.close()
            } finally {
                bridge.close(closing)
            }
        }
    }

    private fun requireHandle(): Long = handle.get().takeIf { it != CLOSED } ?: error("VM session is closed")

    companion object {
        private const val CLOSED = 0L

        fun open(artifact: ByteArray): VmSession = open(artifact, VmRuntime.bridge())

        fun openInStore(
            artifact: ByteArray,
            store: WorldFileSystemStore,
            id: ComputerId,
            romImage: ByteArray,
        ): VmSession {
            val (bridge, result) = store.createMachine(id, romImage.copyOf(), artifact.copyOf())
            val handle = decodeNative { WireDecoder(result).createdHandle() }
            return admitted(handle, bridge)
        }

        fun bootInStore(
            store: WorldFileSystemStore,
            id: ComputerId,
            romImage: ByteArray,
        ): VmSession {
            val (bridge, result) = store.createBootMachine(id, romImage.copyOf())
            val handle = decodeNative { WireDecoder(result).createdHandle() }
            return admitted(handle, bridge)
        }

        internal fun open(
            artifact: ByteArray,
            bridge: LowLevelVmBridge,
        ): VmSession {
            val handle = decodeNative { WireDecoder(bridge.create(artifact.copyOf())).createdHandle() }
            return admitted(handle, bridge)
        }

        private fun admitted(
            handle: Long,
            bridge: LowLevelVmBridge,
        ): VmSession =
            try {
                VmSession(handle, bridge, bridge.openTerminalTransport())
            } catch (error: Throwable) {
                try {
                    bridge.close(handle)
                } catch (closeError: Throwable) {
                    error.addSuppressed(closeError)
                }
                throw error
            }

        private inline fun <T> decodeNative(block: () -> T): T =
            try {
                block()
            } catch (error: VmVerificationException) {
                throw error
            } catch (error: VmAdmissionException) {
                throw error
            } catch (error: VmStartException) {
                throw error
            } catch (error: VmBootException) {
                throw error
            } catch (error: VmBridgeException) {
                throw error
            } catch (error: Exception) {
                throw VmBridgeException("invalid native VM result", error)
            }
    }
}

private class GenerationWireDecoder(
    bytes: ByteArray,
) {
    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    fun generation(): Long {
        require(buffer.get().toInt() and 0xff == 1) { "unsupported filesystem generation wire version" }
        val generation = buffer.long
        require(generation >= 0) { "native filesystem generation exceeds the JVM range" }
        require(!buffer.hasRemaining()) { "native filesystem generation contains trailing bytes" }
        return generation
    }
}

private class WireDecoder(
    bytes: ByteArray,
) {
    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    fun createdHandle(): Long =
        when (u8()) {
            0 -> i64().also { require(it != 0L) { "native VM returned a zero handle" } }
            1 -> throw VmVerificationException()
            2 -> throw VmAdmissionException(u16())
            3 -> throw VmStartException(u16())
            4 -> throw VmBridgeException("native VM handle allocation failed with code ${u8()}")
            5 -> throw VmBootException(u8())
            else -> invalid()
        }.also { end() }

    fun outcome(): VmOutcome =
        when (u8()) {
            0 -> VmOutcome.SliceExhausted
            1 -> VmOutcome.HostRequest(request())
            2 -> VmOutcome.AllocationExhausted(boolean())
            3 -> VmOutcome.QuotaExhausted(quotaKind(u8()), i64(), i64())
            4 -> VmOutcome.Halted(optionalValue())
            5 -> VmOutcome.Crashed(guestTrap(u8()))
            6 -> VmOutcome.Faulted(vmFault(u8()))
            7 -> VmOutcome.HostFailed(hostFailureKind(u8()), u32())
            9 -> VmOutcome.WaitingForTerminalEvent
            else -> invalid()
        }.also { end() }

    private fun request(): VmHostRequest =
        VmHostRequest(
            id = i64(),
            capability = CapabilityIdentity(text(), text(), u16(), u16()),
            operation = i32(),
            arguments = List(i32().boundedCount()) { value() },
        )

    private fun value(): VmValue =
        when (u8()) {
            1 -> VmValue.I32(i32())
            2 -> VmValue.I64(i64())
            3 -> VmValue.F32(i32())
            4 -> VmValue.F64(i64())
            5 -> VmValue.Bool(boolean())
            6 -> VmValue.CharValue(u16().toChar())
            7 -> VmValue.StringValue(String(CharArray(i32().boundedCount()) { u16().toChar() }))
            else -> invalid()
        }

    private fun text(): String {
        val bytes = ByteArray(i32().boundedCount())
        buffer.get(bytes)
        return StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun Int.boundedCount(): Int = also { require(it >= 0 && it <= buffer.remaining()) { "invalid native VM length" } }

    private fun u8(): Int = buffer.get().toInt() and 0xff

    private fun u16(): Int = buffer.short.toInt() and 0xffff

    private fun i32(): Int = buffer.int

    private fun u32(): Long = buffer.int.toLong() and 0xffff_ffffL

    private fun i64(): Long = buffer.long

    private fun optionalValue(): VmValue? =
        when (u8()) {
            0 -> null
            1 -> value()
            else -> invalid()
        }

    private fun boolean(): Boolean =
        when (u8()) {
            0 -> false
            1 -> true
            else -> invalid()
        }

    private fun guestTrap(code: Int): GuestTrap = GuestTrap.entries.firstOrNull { it.wireCode == code } ?: invalid()

    private fun vmFault(code: Int): VmFault = VmFault.entries.firstOrNull { it.wireCode == code } ?: invalid()

    private fun quotaKind(code: Int): QuotaKind = QuotaKind.entries.firstOrNull { it.wireCode == code } ?: invalid()

    private fun hostFailureKind(code: Int): HostFailureKind = HostFailureKind.entries.firstOrNull { it.wireCode == code } ?: invalid()

    private fun end() = require(!buffer.hasRemaining()) { "native VM result contains trailing bytes" }

    private fun invalid(): Nothing = throw IllegalArgumentException("invalid native VM result")
}

internal class TerminalWireDecoder(
    private val buffer: ByteBuffer,
) {
    init {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
    }

    constructor(bytes: ByteArray) : this(ByteBuffer.wrap(bytes))

    fun fullState(): TerminalState {
        require(u8() == 2) { "native terminal result is not a full state" }
        return state().also { end() }
    }

    fun update(): TerminalUpdate =
        when (u8()) {
            0 -> {
                TerminalUpdate.Unchanged(revision())
            }

            1 -> {
                val base = revision()
                val target = revision()
                require(target > base) { "invalid terminal delta revisions" }
                TerminalUpdate.Delta(base, target, List(count(MAX_CHANGES)) { change() })
            }

            2 -> {
                TerminalUpdate.Full(state())
            }

            else -> {
                invalid()
            }
        }.also { end() }

    private fun state(): TerminalState {
        val revision = revision()
        val width = u16()
        val height = u16()
        require(width == WIDTH && height == HEIGHT) { "unsupported terminal dimensions" }
        val cells = List(count(CELL_COUNT)) { cell() }
        require(cells.size == CELL_COUNT) { "invalid terminal cell count" }
        return TerminalState(revision, width, height, cells, position(), boolean())
    }

    private fun change(): TerminalChange =
        when (u8()) {
            0 -> {
                val start = u16()
                val cells = List(u16()) { cell() }
                require(cells.isNotEmpty() && start + cells.size <= CELL_COUNT) { "invalid terminal patch" }
                TerminalChange.Patch(start, cells)
            }

            1 -> {
                val x = u16()
                val y = u16()
                val width = u16()
                val height = u16()
                require(width > 0 && height > 0 && x + width <= WIDTH && y + height <= HEIGHT) {
                    "invalid terminal fill"
                }
                TerminalChange.Fill(x, y, width, height, cell())
            }

            2 -> {
                val rows = u16()
                require(rows in 1..HEIGHT) { "invalid terminal scroll" }
                TerminalChange.Scroll(rows, cell())
            }

            3 -> {
                TerminalChange.Cursor(position(), boolean())
            }

            4 -> {
                TerminalChange.Reset
            }

            else -> {
                invalid()
            }
        }

    private fun cell(): TerminalCell {
        val codePoint = buffer.int
        val foreground = u8()
        val background = u8()
        require(
            Character.isValidCodePoint(codePoint) && codePoint !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code,
        ) { "invalid terminal Unicode scalar" }
        require(foreground in 0 until PALETTE_SIZE && background in 0 until PALETTE_SIZE) {
            "invalid terminal palette index"
        }
        return TerminalCell(codePoint, foreground, background)
    }

    private fun position(): TerminalPosition {
        val x = u16()
        val y = u16()
        require(x in 0 until WIDTH && y in 0 until HEIGHT) { "invalid terminal cursor" }
        return TerminalPosition(x, y)
    }

    private fun revision(): Long = buffer.long.also { require(it >= 0) { "terminal revision exceeds JVM range" } }

    private fun count(maximum: Int): Int = buffer.int.also { require(it in 0..maximum) { "invalid terminal count" } }

    private fun u8(): Int = buffer.get().toInt() and 0xff

    private fun u16(): Int = buffer.short.toInt() and 0xffff

    private fun boolean(): Boolean =
        when (u8()) {
            0 -> false
            1 -> true
            else -> invalid()
        }

    private fun end() = require(!buffer.hasRemaining()) { "trailing terminal wire bytes" }

    private fun invalid(): Nothing = throw IllegalArgumentException("invalid terminal wire result")

    private companion object {
        const val WIDTH = 51
        const val HEIGHT = 19
        const val CELL_COUNT = WIDTH * HEIGHT
        const val PALETTE_SIZE = 16
        const val MAX_CHANGES = 4_096
    }
}
