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

package ru.lazyhat.compukters.lang.runtime.fs

import ru.lazyhat.compukters.lang.runtime.vm.LowLevelVmBridge
import ru.lazyhat.compukters.lang.runtime.vm.VmBridgeException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorldFileSystemStoreTest {
    @Test
    fun `computer identity is nonzero defensive and big endian`() {
        val source = ByteArray(16) { (it + 1).toByte() }
        val id = ComputerId.of(source)
        source.fill(0)

        assertContentEquals(ByteArray(16) { (it + 1).toByte() }, id.toByteArray())
        assertEquals(0x0102_0304_0506_0708L, id.highBits)
        assertEquals(0x090a_0b0c_0d0e_0f10L, id.lowBits)
        assertFailsWith<IllegalArgumentException> { ComputerId.of(ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { ComputerId.of(ByteArray(16)) }
        assertFailsWith<IllegalArgumentException> { ComputerId.fromLongs(0, 0) }
    }

    @Test
    fun `store owns its handle decodes lifecycle values and closes idempotently`() {
        val bridge = FakeBridge()
        val id = ComputerId.fromLongs(0x0102_0304_0506_0708L, 0x1112_1314_1516_1718L)
        val root = Path.of("/tmp/compukters-kotlin-store")
        val store = WorldFileSystemStore.open(root, bridge)

        assertContentEquals(root.toString().encodeToByteArray(), bridge.openedRoot)
        assertContentEquals(ByteArray(0), bridge.openedLimits)
        assertEquals(FileSystemStoreHealth.ACTIVE, store.health())
        assertEquals(7, store.durableGeneration(id))
        store.flush(id, 7)
        store.tombstone(id)
        store.recover(id)
        store.close()
        store.close()

        val expectedId = id.toByteArray()
        assertEquals(listOf(23L to 7L), bridge.flushes.map { it.first to it.third })
        assertContentEquals(expectedId, bridge.flushes.single().second)
        assertContentEquals(expectedId, bridge.tombstones.single().second)
        assertContentEquals(expectedId, bridge.recoveries.single().second)
        assertEquals(listOf(23L), bridge.closedStores)
        assertFailsWith<IllegalStateException> { store.health() }
    }

    @Test
    fun `store rejects noncanonical roots and malformed native wire`() {
        val bridge = FakeBridge()
        assertFailsWith<IllegalArgumentException> {
            WorldFileSystemStore.open(Path.of("relative"), bridge)
        }
        assertFailsWith<IllegalArgumentException> {
            WorldFileSystemStore.open(Path.of("/tmp/../tmp/store"), bridge)
        }

        bridge.openResult = bytes(1, 1)
        assertEquals(
            FileSystemStoreOpenFailure.ROOT_NOT_ABSOLUTE,
            assertFailsWith<FileSystemStoreOpenException> {
                WorldFileSystemStore.open(Path.of("/tmp/store"), bridge)
            }.failure,
        )
        bridge.openResult = bytes(1, 0, long(23), 99)
        assertFailsWith<VmBridgeException> {
            WorldFileSystemStore.open(Path.of("/tmp/store"), bridge)
        }
    }

    @Test
    fun `failed native close retains ownership for an explicit retry`() {
        val bridge = FakeBridge().also { it.closeFailures = 1 }
        val store = WorldFileSystemStore.open(Path.of("/tmp/compukters-close-retry"), bridge)

        assertFailsWith<VmBridgeException> { store.close() }
        assertEquals(FileSystemStoreHealth.ACTIVE, store.health())
        store.close()

        assertEquals(listOf(23L), bridge.closedStores)
    }

    private class FakeBridge : LowLevelVmBridge {
        var openResult = bytes(1, 0, long(23))
        var openedRoot = ByteArray(0)
        var openedLimits = ByteArray(0)
        val flushes = mutableListOf<Triple<Long, ByteArray, Long>>()
        val tombstones = mutableListOf<Pair<Long, ByteArray>>()
        val recoveries = mutableListOf<Pair<Long, ByteArray>>()
        val closedStores = mutableListOf<Long>()
        var closeFailures = 0

        override fun storeOpen(
            rootUtf8: ByteArray,
            limitsWire: ByteArray,
        ): ByteArray {
            openedRoot = rootUtf8.copyOf()
            openedLimits = limitsWire.copyOf()
            return openResult.copyOf()
        }

        override fun storeHealth(handle: Long): ByteArray = bytes(1, 0)

        override fun storeDurableGeneration(
            handle: Long,
            id: ByteArray,
        ): ByteArray = bytes(1, long(7))

        override fun storeFlush(
            handle: Long,
            id: ByteArray,
            generation: Long,
        ) {
            flushes += Triple(handle, id.copyOf(), generation)
        }

        override fun storeTombstone(
            handle: Long,
            id: ByteArray,
        ) {
            tombstones += handle to id.copyOf()
        }

        override fun storeRecover(
            handle: Long,
            id: ByteArray,
        ) {
            recoveries += handle to id.copyOf()
        }

        override fun storeClose(handle: Long) {
            if (closeFailures > 0) {
                closeFailures--
                throw VmBridgeException("close failed")
            }
            closedStores += handle
        }

        override fun create(artifact: ByteArray): ByteArray = error("unused")

        override fun advance(
            handle: Long,
            guestBudget: Int,
            maintenanceBudget: Int,
        ): ByteArray = error("unused")

        override fun resumeUnit(handle: Long, requestId: Long) = error("unused")

        override fun resumeString(handle: Long, requestId: Long, value: CharArray) = error("unused")

        override fun resumeFailure(handle: Long, requestId: Long, kind: Int, code: Long) = error("unused")

        override fun close(handle: Long) = error("unused")
    }
}

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
    ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
