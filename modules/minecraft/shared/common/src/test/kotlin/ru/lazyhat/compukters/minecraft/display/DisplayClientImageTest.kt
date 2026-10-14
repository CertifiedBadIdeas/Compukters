/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayClientImageTest {
    @Test
    fun `a multi packet frame becomes visible only when all loaded tiles arrive`() {
        val first = DisplayClientImage()
        val second = DisplayClientImage()
        val id = UUID.randomUUID().toString()
        val red = ByteArray(16 * 16 * 3) { 1 }
        val blue = ByteArray(16 * 16 * 3) { 2 }
        val peers = mapOf(1L to first, 2L to second)
        first.receive(0, red, id, 1, "1,2")
        first.publish(peers::get)
        assertEquals(0, first.rgb[0].toInt())
        second.receive(0, blue, id, 1, "1,2")
        second.publish(peers::get)
        assertEquals(1, first.rgb[0].toInt())
        assertEquals(2, second.rgb[0].toInt())
        first.receive(0, blue, id, 0, "1,2")
        first.publish(peers::get)
        assertEquals(1, first.rgb[0].toInt())
    }

    @Test
    fun `unloaded and missing panels do not block publication and malformed tiles are rejected`() {
        val panel = DisplayClientImage()
        val id = UUID.randomUUID().toString()
        panel.receive(0, ByteArray(16 * 16 * 3) { 3 }, id, 2, "1,2")
        panel.publish { if (it == 1L) panel else null }
        assertEquals(3, panel.rgb[0].toInt())
        panel.receive(3, byteArrayOf(4), id, 3, "1,2")
        panel.publish { panel }
        assertEquals(3, panel.rgb[0].toInt())
    }

    @Test
    fun `rectangles and empty acknowledgments preserve a coherent surface without uploading unchanged panels`() {
        val first = DisplayClientImage()
        val second = DisplayClientImage()
        val peers = mapOf(1L to first, 2L to second)
        val id = UUID.randomUUID().toString()
        val initial = ByteArray(16 * 16 * 3) { 1 }
        for (image in peers.values) image.receive(0, initial, id, 1, "1,2")
        first.publish(peers::get)
        val unchangedRevision = second.revision
        val changed = initial.copyOf()
        val offset = (4 * 16 + 3) * 3
        changed[offset] = 12
        changed[offset + 1] = 34
        changed[offset + 2] = 56
        val patch = DisplayTileUpdate.between(initial, changed, 16, 1)
        assertEquals(3, patch.rgb.size)
        first.receive(0, patch.rgb, id, 2, "1,2", patch.base, patch.x, patch.y, patch.width, patch.height)
        first.publish(peers::get)
        assertContentEquals(initial, first.rgb)
        second.receive(0, byteArrayOf(), id, 2, "1,2", 1, 0, 0, 0, 0)
        first.publish(peers::get)
        assertContentEquals(changed, first.rgb)
        assertContentEquals(initial, second.rgb)
        assertEquals(2L, first.publication)
        assertEquals(2L, second.publication)
        assertEquals(unchangedRevision, second.revision)
    }

    @Test
    fun `missing bases request recovery and a full snapshot supersedes the rejected delta`() {
        val image = DisplayClientImage()
        val id = UUID.randomUUID().toString()
        val initial = ByteArray(16 * 16 * 3) { 1 }
        image.receive(0, initial, id, 1, "1")
        image.publish { image }
        image.receive(0, byteArrayOf(2, 3, 4), id, 3, "1", 2, 0, 0, 1, 1)
        assertTrue(image.needsSnapshot)
        image.publish { image }
        assertContentEquals(initial, image.rgb)
        // A delayed intermediate update must not cancel recovery for the already missed newer one.
        image.receive(0, byteArrayOf(5, 6, 7), id, 2, "1", 1, 0, 0, 1, 1)
        assertTrue(image.needsSnapshot)
        image.publish { image }
        assertEquals(2L, image.publication)
        val full = ByteArray(initial.size) { 4 }
        image.receive(0, full, id, 3, "1")
        assertFalse(image.needsSnapshot)
        image.publish { image }
        assertContentEquals(full, image.rgb)
        // Stale patches cannot corrupt a recovered image.
        image.receive(0, byteArrayOf(9, 9, 9), id, 2, "1", 1, 0, 0, 1, 1)
        image.publish { image }
        assertContentEquals(full, image.rgb)
    }

    @Test
    fun `pending deltas compose before rendering and malformed bounds never mutate pixels`() {
        val image = DisplayClientImage()
        val id = UUID.randomUUID().toString()
        image.receive(0, ByteArray(16 * 16 * 3), id, 1, "1")
        image.receive(0, byteArrayOf(1, 2, 3), id, 2, "1", 1, 2, 3, 1, 1)
        image.receive(0, byteArrayOf(4, 5, 6), id, 3, "1", 2, 3, 3, 1, 1)
        image.publish { image }
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6), image.rgb.copyOfRange((3 * 16 + 2) * 3, (3 * 16 + 4) * 3))
        val before = image.rgb.copyOf()
        image.receive(0, byteArrayOf(9, 9, 9), id, 4, "1", 3, Int.MAX_VALUE, 0, 1, 1)
        image.receive(0, byteArrayOf(9, 9, 9), id, 4, "1", 3, 16, 0, 1, 1)
        image.receive(0, byteArrayOf(), id, 4, "1", -1, 0, 0, 0, 0)
        image.publish { image }
        assertContentEquals(before, image.rgb)
        assertEquals(3L, image.publication)
    }

    @Test
    fun `delta encoder round trips all densities and falls back to full for changed modes`() {
        for (density in listOf(16, 32, 64, 128)) {
            val initial = ByteArray(density * density * 3)
            val changed = initial.copyOf()
            for (y in 3..7) {
                for (x in 2..9) {
                    val offset = (y * density + x) * 3
                    changed[offset] = x.toByte()
                    changed[offset + 1] = y.toByte()
                    changed[offset + 2] = 127
                }
            }
            val patch = DisplayTileUpdate.between(initial, changed, density, 1)
            assertEquals(8 * 5 * 3, patch.rgb.size)
            val image = DisplayClientImage()
            val mode =
                ru.lazyhat.compukters.core.display.DisplayCanvas.DENSITIES
                    .indexOf(density)
            val id = UUID.randomUUID().toString()
            image.receive(mode, initial, id, 1, "1")
            image.publish { image }
            image.receive(mode, patch.rgb, id, 2, "1", patch.base, patch.x, patch.y, patch.width, patch.height)
            image.publish { image }
            assertContentEquals(changed, image.rgb)
            assertEquals(-1L, DisplayTileUpdate.between(byteArrayOf(), changed, density, 2).base)
            assertEquals(0, DisplayTileUpdate.between(changed, changed, density, 2).rgb.size)
        }
    }

    @Test
    fun `late packets from the previous assembly cannot replace a newer surface`() {
        val image = DisplayClientImage()
        val oldId = UUID.randomUUID().toString()
        val newId = UUID.randomUUID().toString()
        image.receive(0, ByteArray(16 * 16 * 3) { 1 }, oldId, 1, "1")
        image.publish { image }
        image.receive(0, ByteArray(16 * 16 * 3) { 2 }, newId, 3, "1")
        image.publish { image }
        image.receive(0, ByteArray(16 * 16 * 3) { 9 }, oldId, 2, "1")
        image.publish { image }
        assertEquals(3L, image.publication)
        assertEquals(2, image.rgb[0].toInt())
    }
}
