/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

class WorldDisplayTileTest {
    private fun tile() =
        WorldDisplayTile(
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            2,
            0,
            255,
            listOf(1),
            -1,
            0,
            0,
            16,
            16,
            ByteArray(16 * 16 * 3),
        )

    @Test
    fun `only bounded full tiles and valid delta rectangles are admitted`() {
        val full = tile()
        full.copy(base = 1, width = 1, height = 1, rgb = byteArrayOf(1, 2, 3))
        full.copy(base = 1, width = 0, height = 0, rgb = byteArrayOf())
        assertFailsWith<IllegalArgumentException> { full.copy(mode = 4) }
        assertFailsWith<IllegalArgumentException> { full.copy(publication = -1) }
        assertFailsWith<IllegalArgumentException> { full.copy(base = 2) }
        assertFailsWith<IllegalArgumentException> { full.copy(opacity = 256) }
        assertFailsWith<IllegalArgumentException> { full.copy(members = emptyList()) }
        assertFailsWith<IllegalArgumentException> { full.copy(members = List(65) { it.toLong() }) }
        assertFailsWith<IllegalArgumentException> { full.copy(members = listOf(1, 1)) }
        assertFailsWith<IllegalArgumentException> { full.copy(position = 2) }
        assertFailsWith<IllegalArgumentException> { full.copy(x = Int.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { full.copy(width = 15, rgb = ByteArray(15 * 16 * 3)) }
        assertFailsWith<IllegalArgumentException> { full.copy(base = 1, height = 0, rgb = byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { full.copy(rgb = ByteArray(1)) }
    }
}
