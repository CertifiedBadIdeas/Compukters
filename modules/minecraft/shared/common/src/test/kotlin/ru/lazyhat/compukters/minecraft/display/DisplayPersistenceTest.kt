/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import ru.lazyhat.compukters.core.display.DisplayPanel
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayPersistenceTest {
    @Test
    fun `Minecraft saved data restores RGB text mode and hole pixels with a free lease`() {
        val storage = DisplayStorage()
        val id = UUID.randomUUID()
        val screen = storage.directory.create(3, 5, 7, 0, 2, 2, listOf(DisplayPanel(0, 0, id)))
        val owner = Any()
        screen.canvas.acquire(owner) { true }
        screen.canvas.setMode(owner, 1)
        DisplayRasterFont.draw(screen.canvas, owner, 0, 0, "Ready Ж", 0xFE8001, 1)
        assertTrue(screen.canvas.encodeRgb().any { it != 0.toByte() })
        screen.canvas.pixel(owner, 40, 40, 0x112233)
        val expected = screen.canvas.encodeRgb()
        screen.canvas.release(owner)
        val restored = DisplayStorageTestPersistence.roundTrip(storage)
        val actual = requireNotNull(restored.directory.byPanel(id))
        assertEquals(screen.id, actual.id)
        assertEquals(1, actual.canvas.mode)
        assertEquals(0x112233, actual.canvas.pixelAt(40, 40))
        assertContentEquals(expected, actual.canvas.encodeRgb())
        assertFalse(actual.canvas.owns(owner))
        actual.canvas.acquire(Any()) { true }
    }
}
