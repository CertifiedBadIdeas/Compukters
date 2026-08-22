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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

class VmSession private constructor(
    handle: Long,
    private val bridge: LowLevelVmBridge,
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

    override fun close() {
        val closing = handle.getAndSet(CLOSED)
        if (closing != CLOSED) bridge.close(closing)
    }

    private fun requireHandle(): Long = handle.get().takeIf { it != CLOSED } ?: error("VM session is closed")

    companion object {
        private const val CLOSED = 0L

        fun open(artifact: ByteArray): VmSession = open(artifact, NativeBridge)

        internal fun open(
            artifact: ByteArray,
            bridge: LowLevelVmBridge,
        ): VmSession {
            val handle = decodeNative { WireDecoder(bridge.create(artifact.copyOf())).createdHandle() }
            return VmSession(handle, bridge)
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
            } catch (error: VmBridgeException) {
                throw error
            } catch (error: Exception) {
                throw VmBridgeException("invalid native VM result", error)
            }
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
