/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.display

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DisplayDirectoryTest {
    @Test
    fun `holes and last panel destruction have distinct persistent lifetimes`() {
        val directory = DisplayDirectory()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val surface = directory.create(0, 10, 0, 0, 3, 2, listOf(DisplayPanel(0, 0, first), DisplayPanel(2, 1, second)))
        val owner = Any()
        surface.canvas.acquire(owner) { true }
        surface.canvas.pixel(owner, 64, 64, 0x123456)
        directory.remove(first)
        val restored = DisplayDirectory.decode(directory.encode())
        val saved = requireNotNull(restored.byPanel(second))
        assertEquals(surface.id, saved.id)
        assertEquals(0x123456, saved.canvas.pixelAt(64, 64))
        val replacement = UUID.randomUUID()
        restored.join(saved.id, DisplayPanel(1, 1, replacement))
        assertEquals(0x123456, saved.canvas.pixelAt(64, 64))
        restored.remove(second)
        restored.remove(replacement)
        assertNull(restored.byId(saved.id))
        assertEquals(0, DisplayDirectory.decode(restored.encode()).snapshot().size)
    }

    @Test
    fun `expansion preserves identity mode world pixels and holes and discards private frames`() {
        val directory = DisplayDirectory()
        val panel = UUID.randomUUID()
        val old = directory.create(10, 10, 10, 0, 2, 1, listOf(DisplayPanel(0, 0, panel)))
        directory.rename(old.id, "panel")
        val owner = Any()
        old.canvas.acquire(owner) { true }
        old.canvas.setMode(owner, 0)
        old.canvas.pixel(owner, 17, 3, 0x123456)
        old.canvas.begin(owner)
        old.canvas.pixel(owner, 17, 3, 0xFFFFFF)
        val grown = directory.expand(old.id, 11, 11, 10, 4, 3, 1, 1)
        assertEquals(old.id, grown.id)
        assertEquals("panel", grown.name)
        assertEquals(0, grown.canvas.mode)
        assertEquals(listOf(DisplayPanel(1, 1, panel)), grown.panels)
        assertEquals(0x123456, grown.canvas.pixelAt(33, 19))
        assertEquals(0, grown.canvas.pixelAt(0, 0))
        kotlin.test.assertFalse(old.canvas.owns(owner))
        val saved = DisplayDirectory.decode(directory.encode()).byId(old.id)!!
        assertEquals(grown.panels, saved.panels)
        assertEquals(0x123456, saved.canvas.pixelAt(33, 19))
    }

    @Test
    fun `invalid expansion leaves geometry pixels and ownership unchanged`() {
        val directory = DisplayDirectory()
        val old = directory.create(0, 0, 0, 0, 2, 2, listOf(DisplayPanel(0, 0, UUID.randomUUID())))
        val owner = Any()
        old.canvas.acquire(owner) { true }
        old.canvas.pixel(owner, 0, 0, 123)
        val before = directory.encode()
        assertFailsWith<IllegalArgumentException> { directory.expand(old.id, 0, 0, 0, 9, 2, 0, 0) }
        assertFailsWith<IllegalArgumentException> { directory.expand(old.id, 0, 0, 0, 1, 2, 0, 0) }
        assertFailsWith<IllegalArgumentException> { directory.expand(old.id, 1, 0, 0, 3, 2, 0, 0) }
        kotlin.test.assertContentEquals(before, directory.encode())
        kotlin.test.assertTrue(old.canvas.owns(owner))
    }

    @Test
    fun `splitting crops published tiles preserves mode and removes names holes and old identity`() {
        for (facing in 0..3) {
            val directory = DisplayDirectory()
            val first = UUID.randomUUID()
            val second = UUID.randomUUID()
            val old = directory.create(10, 10, 10, facing, 3, 2, listOf(DisplayPanel(0, 0, first), DisplayPanel(2, 1, second)))
            directory.rename(old.id, "screen")
            val owner = Any()
            old.canvas.acquire(owner) { true }
            old.canvas.setMode(owner, 0)
            old.canvas.pixel(owner, 0, 0, 0x123456)
            old.canvas.pixel(owner, 32, 16, 0x654321)
            old.canvas.begin(owner)
            old.canvas.fill(owner, 0, 0, 48, 32, 0xFFFFFF)
            val pieces = directory.split(old.id)
            assertEquals(2, pieces.size)
            assertNull(directory.byId(old.id))
            kotlin.test.assertFalse(old.canvas.owns(owner))
            assertEquals(0x123456, directory.byPanel(first)!!.canvas.pixelAt(0, 0))
            assertEquals(0x654321, directory.byPanel(second)!!.canvas.pixelAt(0, 0))
            val b = directory.byPanel(second)!!
            assertEquals(9, b.originY)
            assertEquals(
                10 +
                    when (facing) {
                        0 -> -2
                        1 -> 2
                        else -> 0
                    },
                b.originX,
            )
            assertEquals(
                10 +
                    when (facing) {
                        2 -> 2
                        3 -> -2
                        else -> 0
                    },
                b.originZ,
            )
            pieces.forEach { piece ->
                assertEquals(1, piece.canvas.columns)
                assertEquals(1, piece.canvas.rows)
                assertEquals(0, piece.canvas.mode)
                assertNull(piece.name)
            }
            assertEquals(2, DisplayDirectory.decode(directory.encode()).snapshot().size)
        }
    }

    @Test
    fun `screen limit rejects splitting without deleting membership image or writer`() {
        val directory = DisplayDirectory()
        val old =
            directory.create(
                0,
                0,
                0,
                0,
                2,
                1,
                listOf(DisplayPanel(0, 0, UUID.randomUUID()), DisplayPanel(1, 0, UUID.randomUUID())),
            )
        repeat(DisplayDirectory.MAXIMUM_SCREENS - 1) {
            directory.create(0, 0, 0, 0, 1, 1, listOf(DisplayPanel(0, 0, UUID.randomUUID())))
        }
        val owner = Any()
        old.canvas.acquire(owner) { true }
        old.canvas.pixel(owner, 0, 0, 123)
        val before = directory.encode()
        assertFailsWith<IllegalStateException> { directory.split(old.id) }
        kotlin.test.assertContentEquals(before, directory.encode())
        kotlin.test.assertTrue(old.canvas.owns(owner))
    }

    @Test
    fun `decode rejects truncated trailing and invalid payloads`() {
        val directory = DisplayDirectory()
        directory.create(0, 0, 0, 0, 1, 1, listOf(DisplayPanel(0, 0, UUID.randomUUID())))
        val bytes = directory.encode()
        assertFailsWith<IllegalArgumentException> { DisplayDirectory.decode(bytes + byteArrayOf(0)) }
        assertFailsWith<IllegalArgumentException> { DisplayDirectory.decode(bytes.copyOf(bytes.size - 1)) }
        assertFailsWith<IllegalArgumentException> { DisplayDirectory.decode(byteArrayOf(0, 0, 0, 9, 0, 0, 0, 0)) }
    }

    @Test
    fun `membership rejects replacement collision and out of canvas joins`() {
        val directory = DisplayDirectory()
        val panel = UUID.randomUUID()
        val surface = directory.create(0, 0, 0, 0, 2, 2, listOf(DisplayPanel(0, 0, panel)))
        assertFailsWith<IllegalArgumentException> { directory.join(surface.id, DisplayPanel(0, 0, UUID.randomUUID())) }
        assertFailsWith<IllegalArgumentException> { directory.join(surface.id, DisplayPanel(2, 0, UUID.randomUUID())) }
        assertFailsWith<IllegalArgumentException> { directory.join(surface.id, DisplayPanel(1, 0, panel)) }
    }
}
