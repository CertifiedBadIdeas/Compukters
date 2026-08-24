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
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VmSessionTest {
    @Test
    fun `boot session copies ROM and does not pass a separate artifact`() {
        val bridge = FakeBridge(createResult = bytes(0, long(19)))
        val store = WorldFileSystemStore.open(Path.of("/tmp/compukters-boot-store"), bridge)
        val rom = byteArrayOf(4, 5, 6)
        val id = ComputerId.fromLongs(9, 10)

        val session = VmSession.bootInStore(store, id, rom)
        rom.fill(0)

        assertEquals(23L, bridge.bootCreate?.storeHandle)
        assertEquals(id.toByteArray().toList(), bridge.bootCreate?.id?.toList())
        assertEquals(listOf<Byte>(4, 5, 6), bridge.bootCreate?.rom?.toList())
        session.close()
        store.close()
    }

    @Test
    fun `boot failure retains its typed Kotlin surface`() {
        val bridge = FakeBridge(createResult = bytes(5, 9))
        val store = WorldFileSystemStore.open(Path.of("/tmp/compukters-boot-failure-store"), bridge)

        assertEquals(
            9,
            assertFailsWith<VmBootException> {
                VmSession.bootInStore(store, ComputerId.fromLongs(1, 2), byteArrayOf(1))
            }.code,
        )
        store.close()
    }

    @Test
    fun `persistent session copies launch inputs and uses its world store handle`() {
        val bridge = FakeBridge(createResult = bytes(0, long(17)))
        val store = WorldFileSystemStore.open(Path.of("/tmp/compukters-session-store"), bridge)
        val artifact = byteArrayOf(1, 2, 3)
        val rom = byteArrayOf(4, 5, 6)
        val id = ComputerId.fromLongs(7, 8)

        val session = VmSession.openInStore(artifact, store, id, rom)
        artifact.fill(0)
        rom.fill(0)

        assertEquals(23L, bridge.persistentCreate?.storeHandle)
        assertEquals(id.toByteArray().toList(), bridge.persistentCreate?.id?.toList())
        assertEquals(listOf<Byte>(4, 5, 6), bridge.persistentCreate?.rom?.toList())
        assertEquals(listOf<Byte>(1, 2, 3), bridge.persistentCreate?.artifact?.toList())
        assertEquals(3, session.filesystemGeneration())
        session.close()
        store.close()
    }

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
    fun `session owns one terminal transport and closes it before its native handle`() {
        val bridge = FakeBridge(createResult = bytes(0, long(7)))
        val state = TerminalState(3, 51, 19, List(969) { TerminalCell(' '.code, 15, 0) }, TerminalPosition(0, 0), true)
        val transport = RecordingTerminalTransport(bridge.lifecycleEvents, state)
        bridge.terminalTransportFactory = {
            bridge.terminalTransportOpens++
            transport
        }

        val session = VmSession.open(byteArrayOf(1), bridge)
        assertEquals(state, session.terminalFullState())
        assertEquals(TerminalUpdate.Unchanged(3), session.terminalChangesSince(3))
        session.close()
        session.close()

        assertEquals(1, bridge.terminalTransportOpens)
        assertEquals(listOf(7L), transport.fullStateHandles)
        assertEquals(listOf(7L to 3L), transport.changeRequests)
        assertEquals(1, transport.closeCount)
        assertEquals(listOf("transport", "handle:7"), bridge.lifecycleEvents)
    }

    @Test
    fun `terminal transport construction failure closes admitted native handle`() {
        val bridge = FakeBridge(createResult = bytes(0, long(13)))
        bridge.terminalTransportFactory = { error("transport failed") }

        assertFailsWith<IllegalStateException> { VmSession.open(byteArrayOf(1), bridge) }

        assertEquals(listOf(13L), bridge.closed)
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
        var persistentCreate: PersistentCreate? = null
        var bootCreate: BootCreate? = null
        var terminalTransportFactory: (() -> TerminalWireTransport)? = null
        var terminalTransportOpens = 0
        val lifecycleEvents = mutableListOf<String>()

        override fun openTerminalTransport(): TerminalWireTransport =
            terminalTransportFactory?.invoke() ?: super<LowLevelVmBridge>.openTerminalTransport()

        override fun filesystemGeneration(handle: Long): ByteArray = bytes(1, long(3))

        override fun storeOpen(
            rootUtf8: ByteArray,
            limitsWire: ByteArray,
        ): ByteArray = bytes(1, 0, long(23))

        override fun storeClose(handle: Long) = Unit

        override fun createInStore(
            storeHandle: Long,
            id: ByteArray,
            rom: ByteArray,
            artifact: ByteArray,
        ): ByteArray {
            persistentCreate = PersistentCreate(storeHandle, id.copyOf(), rom.copyOf(), artifact.copyOf())
            return createResult
        }

        override fun createBootInStore(
            storeHandle: Long,
            id: ByteArray,
            rom: ByteArray,
        ): ByteArray {
            bootCreate = BootCreate(storeHandle, id.copyOf(), rom.copyOf())
            return createResult
        }

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
            lifecycleEvents += "handle:$handle"
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

    private class RecordingTerminalTransport(
        private val lifecycleEvents: MutableList<String>,
        private val state: TerminalState,
    ) : TerminalWireTransport {
        val fullStateHandles = mutableListOf<Long>()
        val changeRequests = mutableListOf<Pair<Long, Long>>()
        var closeCount = 0

        override fun fullState(handle: Long): TerminalState = state.also { fullStateHandles += handle }

        override fun changesSince(
            handle: Long,
            revision: Long,
        ): TerminalUpdate = TerminalUpdate.Unchanged(state.revision).also { changeRequests += handle to revision }

        override fun close() {
            closeCount++
            lifecycleEvents += "transport"
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

    private data class PersistentCreate(
        val storeHandle: Long,
        val id: ByteArray,
        val rom: ByteArray,
        val artifact: ByteArray,
    )

    private data class BootCreate(
        val storeHandle: Long,
        val id: ByteArray,
        val rom: ByteArray,
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
