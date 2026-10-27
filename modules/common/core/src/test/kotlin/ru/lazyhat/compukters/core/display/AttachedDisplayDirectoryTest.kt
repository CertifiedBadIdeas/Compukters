/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.display

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AttachedDisplayDirectoryTest {
    @Test
    fun `six faces preserve geometry pixels identity and attachment index across save move and split`() {
        for (face in 0..5) {
            val directory = DisplayDirectory()
            val panels = listOf(DisplayPanel(0, 0, UUID.randomUUID()), DisplayPanel(1, 1, UUID.randomUUID()))
            val surface = directory.create(10, 20, 30, face, 2, 2, panels, attached = true)
            val owner = Any()
            surface.canvas.acquire(owner) { true }
            surface.canvas.pixel(owner, 70, 70, 0x123456)
            surface.canvas.setOpacity(owner, 128)
            directory.rename(surface.id, "cockpit")
            val restored = DisplayDirectory.decode(directory.encode())
            val saved = restored.byId(surface.id)!!
            assertTrue(saved.attached)
            assertEquals(128, saved.canvas.opacity)
            assertEquals(face, saved.facing)
            assertEquals(0x123456, saved.canvas.pixelAt(70, 70))
            for (panel in panels) {
                val p = DisplayFace.position(saved, panel.column, panel.row)
                assertSame(saved, restored.attachedAt(p.x, p.y, p.z))
            }
            val destination = DisplayDirectory()
            val moved = restored.relocate(saved.id, 100, 200, 300, face, destination)
            assertNull(restored.attachedAt(10, 20, 30))
            assertSame(moved, destination.attachedAt(100, 200, 300))
            val split = destination.split(moved.id)
            assertEquals(2, split.size)
            assertTrue(split.all { it.attached && it.canvas.opacity == 128 })
            for (tile in split) assertSame(tile, destination.attachedAt(tile.originX, tile.originY, tile.originZ))
            assertEquals(0x123456, split[1].canvas.pixelAt(6, 6))
        }
    }

    @Test
    fun `overlap rejection leaves original surfaces and indexes intact`() {
        val directory = DisplayDirectory()
        val surface = directory.create(0, 0, 0, 4, 1, 1, listOf(DisplayPanel(0, 0, UUID.randomUUID())), attached = true)
        assertFailsWith<IllegalArgumentException> {
            directory.create(0, 0, 0, 2, 1, 1, listOf(DisplayPanel(0, 0, UUID.randomUUID())), attached = true)
        }
        assertEquals(listOf(surface), directory.snapshot())
        assertSame(surface, directory.attachedAt(0, 0, 0))
        directory.remove(surface.panels.single().instance)
        assertNull(directory.attachedAt(0, 0, 0))
    }

    @Test
    fun `legacy format one remains readable and upgrades on save`() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(1)
            out.writeInt(1)
            out.writeLong(1)
            out.writeLong(2)
            out.writeInt(10)
            out.writeInt(20)
            out.writeInt(30)
            out.writeInt(0)
            out.writeInt(1)
            out.writeInt(1)
            out.writeInt(0)
            out.writeUTF("old-panel")
            out.writeInt(1)
            out.writeInt(0)
            out.writeInt(0)
            out.writeLong(3)
            out.writeLong(4)
            val rgb = ByteArray(16 * 16 * 3) { 42 }
            out.writeInt(rgb.size)
            out.write(rgb)
        }
        val directory = DisplayDirectory.decode(bytes.toByteArray())
        val surface = directory.snapshot().single()
        assertFalse(surface.attached)
        assertEquals(255, surface.canvas.opacity)
        assertEquals("old-panel", surface.name)
        assertEquals(0x2a2a2a, surface.canvas.pixelAt(0, 0))
        assertFalse(
            DisplayDirectory
                .decode(directory.encode())
                .snapshot()
                .single()
                .attached,
        )
    }

    @Test
    fun `up and down pointer pixels follow the same basis as their tiles`() {
        assertEquals(DisplayClick(16 + 4, 32 + 8), displayClickAt(4, 1, 2, 16, 0.25, 1.0, 0.5))
        assertEquals(DisplayClick(16 + 4, 32 + 8), displayClickAt(5, 1, 2, 16, 0.25, 0.0, 0.5))
        assertNull(displayClickAt(6, 0, 0, 16, 0.0, 0.0, 0.0))
    }
}
