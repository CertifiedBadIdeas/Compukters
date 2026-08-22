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

import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MinimalArtifactGoldenTest {
    @Test
    fun `Kotlin writer reproduces canonical Rust vector A`() {
        val expected = fixture("vector-a.cpkt")
        val result = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(minimalArtifact()))

        assertEquals(1144, result.bytes.size)
        assertContentEquals(expected, result.bytes)
        assertEquals(
            "23a3d933f13f78ac679e0cf10eca0355566f25e7e80a5937e45fb65ce8d06876",
            result.sha256.toHex(),
        )
    }
}

internal fun fixture(name: String): ByteArray = Path.of(requireNotNull(System.getProperty("compukter.vm.fixtures")), name).readBytes()

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
