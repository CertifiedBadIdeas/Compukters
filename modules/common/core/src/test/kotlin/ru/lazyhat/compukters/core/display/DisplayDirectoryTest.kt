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
