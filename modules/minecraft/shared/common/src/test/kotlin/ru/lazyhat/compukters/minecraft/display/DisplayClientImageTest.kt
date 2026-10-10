/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
