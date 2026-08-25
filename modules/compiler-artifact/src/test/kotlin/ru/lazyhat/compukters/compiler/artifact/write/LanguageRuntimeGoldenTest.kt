/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
        assertEquals("968d9b8fbc48fd7f5227837369910f945dba441f08868ae6c0be002a5b452492", result.sha256.toHex())
        assertEquals(
            "ab4e670a18323eab2cc1a734e54f1d78df4f89fb24eb68141f2bc747d2480fd6",
            encodeModuleSections(languageRuntimeArtifact().modules.single(), ArtifactWriteLimits()).semanticHash.toHex(),
        )
    }
}
