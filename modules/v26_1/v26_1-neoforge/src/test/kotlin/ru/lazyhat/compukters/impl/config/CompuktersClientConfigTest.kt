/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

package ru.lazyhat.compukters.impl.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompuktersClientConfigTest {
    @Test
    fun `terminal font config defaults to Cozette and accepts catalog IDs only`() {
        val value = CompuktersClientConfig.terminalFontId
        val specification = value.spec

        assertEquals("cozette", value.default)
        assertTrue(specification.test("cozette"))
        assertTrue(specification.test("dina"))
        assertTrue(specification.test("proggy_tiny"))
        assertFalse(specification.test("missing"))
        assertFalse(specification.test(7))
        assertFalse(specification.test(null))
    }
}
