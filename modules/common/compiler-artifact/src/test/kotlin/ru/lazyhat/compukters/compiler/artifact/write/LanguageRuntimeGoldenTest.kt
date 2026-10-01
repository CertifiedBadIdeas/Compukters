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
        assertEquals(2168, result.bytes.size)
        assertEquals("166a6fd9165a13b6b652097bb54c87047cb52ed466adb19f79d03dc6fbee88ac", result.sha256.toHex())
        assertEquals(
            "098452e9be56d556234b75f4009923a1a06dd311001130aa30b3dab5188eede6",
            ArtifactWriter.moduleSemanticHash(languageRuntimeArtifact().modules.single()).toHex(),
        )
    }
}
