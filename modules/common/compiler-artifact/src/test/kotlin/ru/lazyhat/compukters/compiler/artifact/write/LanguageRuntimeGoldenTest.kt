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
        assertEquals(2064, result.bytes.size)
        assertEquals("d52377096fbe8805e2c9e77b012adca0c31482a12a99c0e6101647babb5f4523", result.sha256.toHex())
        assertEquals(
            "d5b93c84642dda34447e2548ac3a45d5a4399c30e8356fd5d167329b27ceefc4",
            ArtifactWriter.moduleSemanticHash(languageRuntimeArtifact().modules.single()).toHex(),
        )
    }
}
