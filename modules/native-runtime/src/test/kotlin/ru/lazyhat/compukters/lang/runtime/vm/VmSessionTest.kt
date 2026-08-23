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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VmSessionTest {
    @Test
    fun `session owns its opaque handle and closes idempotently`() {
        val bridge = FakeBridge(createResult = bytes(0, long(7)))
        val session = VmSession.open(byteArrayOf(1, 2, 3), bridge)

        session.close()
        session.close()

        assertEquals(listOf(7L), bridge.closed)
        assertFailsWith<IllegalStateException> { session.advance(64, 64) }
    }

    @Test
    fun `advance maps copied slice request waits trap fault quota and host failure outcomes`() {
        val bridge = FakeBridge(createResult = bytes(0, long(11)))
        val session = VmSession.open(byteArrayOf(1), bridge)
        bridge.outcomes += bytes(0)
        bridge.outcomes += request()
        bridge.outcomes += bytes(2, 1)
        bridge.outcomes += bytes(4, 1, 1, int(23))
        bridge.outcomes += bytes(5, 0)
        bridge.outcomes += bytes(6, 7)
        bridge.outcomes += bytes(3, 1, long(4), long(3))
        bridge.outcomes += bytes(7, 0, int(17))
        bridge.outcomes += bytes(9)

        assertEquals(VmOutcome.SliceExhausted, session.advance(64, 64))
        assertEquals(
            VmOutcome.HostRequest(
                VmHostRequest(
                    id = 9,
                    capability = CapabilityIdentity("compukter", "terminal", 1, 0),
                    operation = 0,
                    arguments = listOf(VmValue.StringValue("A\ud800\udc00")),
                ),
            ),
            session.advance(64, 64),
        )
        assertEquals(VmOutcome.AllocationExhausted(collectionAttempted = true), session.advance(64, 64))
        assertEquals(VmOutcome.Halted(VmValue.I32(23)), session.advance(64, 64))
        assertEquals(VmOutcome.Crashed(GuestTrap.DIVISION_BY_ZERO), session.advance(64, 64))
        assertEquals(VmOutcome.Faulted(VmFault.HANDLE_EXHAUSTED), session.advance(64, 64))
        assertEquals(VmOutcome.QuotaExhausted(QuotaKind.HOST_REQUESTS, 4, 3), session.advance(64, 64))
        assertEquals(VmOutcome.HostFailed(HostFailureKind.END_OF_FILE, 17), session.advance(64, 64))
        assertEquals(VmOutcome.WaitingForTerminalEvent, session.advance(64, 64))
    }

    @Test
    fun `typed host responses are forwarded with the pending request id`() {
        val bridge = FakeBridge(createResult = bytes(0, long(11)))
        val session = VmSession.open(byteArrayOf(1), bridge)

        session.resume(7, HostResponse.UnitSuccess)
        session.resume(8, HostResponse.StringSuccess("A\ud800B"))
        session.resume(9, HostResponse.Failure(HostFailureKind.END_OF_FILE, 17))

        assertEquals(listOf(11L to 7L), bridge.unitResponses)
        assertEquals(listOf(Triple(11L, 8L, "A\ud800B".toCharArray().toList())), bridge.stringResponses)
        assertEquals(listOf(FailureResponse(11, 9, 0, 17)), bridge.failures)
    }

    @Test
    fun `malformed native output is rejected as a bridge failure`() {
        val truncatedCreate = FakeBridge(createResult = bytes(0, 1))
        assertFailsWith<VmBridgeException> { VmSession.open(byteArrayOf(1), truncatedCreate) }

        val bridge = FakeBridge(createResult = bytes(0, long(11)))
        val session = VmSession.open(byteArrayOf(1), bridge)
        bridge.outcomes += bytes(0, 99)
        assertFailsWith<VmBridgeException> { session.advance(64, 64) }

        bridge.outcomes += bytes(2, 2)
        assertFailsWith<VmBridgeException> { session.advance(64, 64) }
    }

    @Test
    fun `terminal state and inputs use immutable bounded bridge values`() {
        val bridge = FakeBridge(createResult = bytes(0, long(11)))
        val session = VmSession.open(byteArrayOf(1), bridge)
        val cells =
            buildList {
                add(cell('>'.code, 15, 0))
                repeat(968) { add(cell(' '.code, 15, 0)) }
            }.fold(ByteArray(0), ByteArray::plus)
        bridge.terminalState =
            bytes(2, long(3), short(51), short(19), int(969), cells, short(50), short(18), 1)
        bridge.terminalUpdate = bytes(0, long(3))

        val state = session.terminalFullState()
        assertEquals(3, state.revision)
        assertEquals(TerminalCell('>'.code, 15, 0), state.cells.first())
        assertEquals(TerminalPosition(50, 18), state.cursor)
        assertEquals(TerminalUpdate.Unchanged(3), session.terminalChangesSince(3))

        session.commitTerminal()
        session.sendTerminalKey(TerminalKey.ENTER, TerminalKeyAction.REPEAT, setOf(TerminalModifier.CONTROL))
        session.sendTerminalText("λ😀")
        assertEquals(1, bridge.terminalCommits)
        assertEquals(listOf(TerminalKeyInput(13, 1, 2)), bridge.terminalKeys)
        assertEquals(listOf(listOf('λ'.code, 0x1f600)), bridge.terminalTexts.map(IntArray::toList))
    }

    @Test
    fun `malformed terminal counts and trailing bytes are bridge failures`() {
        val bridge = FakeBridge(createResult = bytes(0, long(11)))
        val session = VmSession.open(byteArrayOf(1), bridge)
        bridge.terminalState = bytes(2, long(0), short(51), short(19), int(Int.MAX_VALUE))
        assertFailsWith<VmBridgeException> { session.terminalFullState() }

        bridge.terminalUpdate = bytes(0, long(0), 99)
        assertFailsWith<VmBridgeException> { session.terminalChangesSince(0) }
    }

    @Test
    fun `create failures retain their typed Kotlin surface`() {
        assertFailsWith<VmVerificationException> { VmSession.open(byteArrayOf(1), FakeBridge(bytes(1))) }
        assertEquals(
            7,
            assertFailsWith<VmAdmissionException> {
                VmSession.open(byteArrayOf(1), FakeBridge(bytes(2, short(7))))
            }.code,
        )
        assertEquals(
            8,
            assertFailsWith<VmStartException> {
                VmSession.open(byteArrayOf(1), FakeBridge(bytes(3, short(8))))
            }.code,
        )
        assertFailsWith<VmBridgeException> { VmSession.open(byteArrayOf(1), FakeBridge(bytes(4, 9))) }
    }

    private fun request(): ByteArray =
        bytes(
            1,
            long(9),
            int(9),
            "compukter".encodeToByteArray(),
            int(8),
            "terminal".encodeToByteArray(),
            short(1),
            short(0),
            int(0),
            int(1),
            7,
            int(3),
            short(0x41),
            short(0xd800),
            short(0xdc00),
        )

    private class FakeBridge(
        private val createResult: ByteArray,
    ) : LowLevelVmBridge {
        val outcomes = ArrayDeque<ByteArray>()
        val closed = mutableListOf<Long>()
        val unitResponses = mutableListOf<Pair<Long, Long>>()
        val stringResponses = mutableListOf<Triple<Long, Long, List<Char>>>()
        val failures = mutableListOf<FailureResponse>()
        var terminalState = ByteArray(0)
        var terminalUpdate = ByteArray(0)
        var terminalCommits = 0
        val terminalKeys = mutableListOf<TerminalKeyInput>()
        val terminalTexts = mutableListOf<IntArray>()

        override fun create(artifact: ByteArray): ByteArray = createResult

        override fun advance(
            handle: Long,
            guestBudget: Int,
            maintenanceBudget: Int,
        ): ByteArray = outcomes.removeFirst()

        override fun resumeUnit(
            handle: Long,
            requestId: Long,
        ) {
            unitResponses += handle to requestId
        }

        override fun resumeString(
            handle: Long,
            requestId: Long,
            value: CharArray,
        ) {
            stringResponses += Triple(handle, requestId, value.toList())
        }

        override fun resumeFailure(
            handle: Long,
            requestId: Long,
            kind: Int,
            code: Long,
        ) {
            failures += FailureResponse(handle, requestId, kind, code)
        }

        override fun close(handle: Long) {
            closed += handle
        }

        override fun terminalCommit(handle: Long) {
            terminalCommits++
        }

        override fun terminalFullState(handle: Long): ByteArray = terminalState

        override fun terminalChangesSince(
            handle: Long,
            revision: Long,
        ): ByteArray = terminalUpdate

        override fun terminalKey(
            handle: Long,
            key: Int,
            action: Int,
            modifiers: Int,
        ) {
            terminalKeys += TerminalKeyInput(key, action, modifiers)
        }

        override fun terminalText(
            handle: Long,
            codePoints: IntArray,
        ) {
            terminalTexts += codePoints
        }
    }

    private data class FailureResponse(
        val handle: Long,
        val requestId: Long,
        val kind: Int,
        val code: Long,
    )

    private data class TerminalKeyInput(
        val key: Int,
        val action: Int,
        val modifiers: Int,
    )
}

private fun cell(
    codePoint: Int,
    foreground: Int,
    background: Int,
): ByteArray = bytes(int(codePoint), foreground, background)

private fun bytes(vararg parts: Any): ByteArray =
    parts
        .flatMap { part ->
            when (part) {
                is Int -> listOf(part.toByte())
                is ByteArray -> part.toList()
                else -> error("unsupported test wire part: $part")
            }
        }.toByteArray()

private fun long(value: Long): ByteArray =
    ByteBuffer
        .allocate(Long.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putLong(value)
        .array()

private fun int(value: Int): ByteArray =
    ByteBuffer
        .allocate(Int.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(value)
        .array()

private fun short(value: Int): ByteArray =
    ByteBuffer
        .allocate(Short.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(value.toShort())
        .array()
