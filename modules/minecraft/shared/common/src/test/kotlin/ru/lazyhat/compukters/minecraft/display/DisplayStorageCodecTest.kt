/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import net.minecraft.nbt.NbtOps
import ru.lazyhat.compukters.core.display.DisplayDirectory
import ru.lazyhat.compukters.core.display.DisplayPanel
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DisplayStorageCodecTest {
    @Test
    fun `world NBT stores complete RGB pixels modes and holes without writer state`() {
        val directory = DisplayDirectory()
        val panel = UUID.randomUUID()
        val screen = directory.create(3, 5, 7, 2, 3, 2, listOf(DisplayPanel(0, 0, panel)))
        val owner = Any()
        screen.canvas.acquire(owner) { true }
        screen.canvas.setMode(owner, 0)
        screen.canvas.pixel(owner, 20, 18, 0xFE8001)
        screen.canvas.begin(owner)
        screen.canvas.pixel(owner, 20, 18, 0xFFFFFF)
        val nbt = DisplayStorageCodec.codec.encodeStart(NbtOps.INSTANCE, directory.encode()).getOrThrow()
        val restored = DisplayDirectory.decode(DisplayStorageCodec.codec.parse(NbtOps.INSTANCE, nbt).getOrThrow())
        val actual = requireNotNull(restored.byPanel(panel))
        assertEquals(0, actual.canvas.mode)
        assertEquals(0xFE8001, actual.canvas.pixelAt(20, 18))
        assertContentEquals(screen.canvas.encodeRgb(), actual.canvas.encodeRgb())
        actual.canvas.acquire(Any()) { true }
    }
}
