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

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LanguageRuntimeGoldenTest {
    @Test
    fun `Kotlin writer reproduces non-trivial Rust language runtime artifact`() {
        val result = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(languageRuntimeArtifact()))

        assertContentEquals(fixture("language-runtime.cpkt"), result.bytes)
        assertEquals(1552, result.bytes.size)
        assertEquals("7ace82d2b36a81403ba73a53aba83f916405ab2ba2868a0f236d97a57cd269e5", result.sha256.toHex())
        assertEquals(
            "ab4e670a18323eab2cc1a734e54f1d78df4f89fb24eb68141f2bc747d2480fd6",
            encodeModuleSections(languageRuntimeArtifact().modules.single(), ArtifactWriteLimits()).semanticHash.toHex(),
        )
    }
}
