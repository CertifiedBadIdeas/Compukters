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
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.compukters.compiler.artifact.write

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SectionEncoderTest {
    @Test
    fun `vector A sections have canonical sizes and bytes`() {
        val encoded = encodeModuleSections(minimalArtifact().modules.single(), ArtifactWriteLimits())

        assertEquals(40, encoded.required(STRINGS).payload.size)
        assertEquals(44, encoded.required(TYPES).payload.size)
        assertEquals(24, encoded.required(CONSTANTS).payload.size)
        assertEquals(60, encoded.required(FUNCTIONS).payload.size)
        assertEquals(48, encoded.required(BLOCKS).payload.size)
        assertEquals(30, encoded.required(CODE).payload.size)
        assertContentEquals(
            byteArrayOf(0xe3.toByte(), 0, 6, 0, 0xff.toByte(), 0xff.toByte()),
            encoded.required(CODE).payload.copyOfRange(24, 30),
        )
    }

    @Test
    fun `vector A module semantic digest matches Rust authority`() {
        val encoded = encodeModuleSections(minimalArtifact().modules.single(), ArtifactWriteLimits())
        assertEquals(
            "f1379df5fe4e751a1df57cf6be2d1575956f8c3e3ebaabe795820b44de2185ee",
            encoded.semanticHash.joinToString("") { "%02x".format(it) },
        )
        assertEquals(32, MessageDigest.getInstance("SHA-256").digest().size)
    }
}
