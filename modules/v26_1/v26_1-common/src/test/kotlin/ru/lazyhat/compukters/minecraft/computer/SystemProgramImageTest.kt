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

package ru.lazyhat.compukters.minecraft.computer

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class SystemProgramImageTest {
    @Test
    fun `packaged extensionless programs are present and returned defensively`() {
        listOf(SystemProgramImage::boot, SystemProgramImage::shell).forEach { load ->
            val first = load()
            val second = load()

            assertTrue(first.isNotEmpty())
            assertContentEquals(first, second)
            first[0] = first[0].inc()
            assertTrue(!first.contentEquals(load()))
        }
    }
}
