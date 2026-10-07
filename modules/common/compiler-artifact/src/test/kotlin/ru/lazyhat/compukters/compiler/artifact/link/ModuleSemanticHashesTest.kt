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

package ru.lazyhat.compukters.compiler.artifact.link

import ru.lazyhat.compukters.compiler.artifact.model.MetadataText
import ru.lazyhat.compukters.compiler.artifact.model.Module
import ru.lazyhat.compukters.compiler.artifact.model.ModuleKind
import ru.lazyhat.compukters.compiler.artifact.model.StringId
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ModuleSemanticHashesTest {
    @Test
    fun `same instance is hashed once and returned bytes cannot modify the cached hash`() {
        val module = module()
        var calls = 0
        val hashes =
            ModuleSemanticHashes {
                calls++
                ArtifactWriter.moduleSemanticHash(it)
            }
        val expected = ArtifactWriter.moduleSemanticHash(module)
        val result = hashes[module]
        result.fill(0)

        assertContentEquals(expected, hashes[module])
        assertEquals(1, calls)
    }

    @Test
    fun `copies are hashed independently and another operation starts with an empty cache`() {
        val original = module()
        val changed = original.copy(strings = listOf(MetadataText.of("changed")))
        var calls = 0
        val hashes =
            ModuleSemanticHashes {
                calls++
                ArtifactWriter.moduleSemanticHash(it)
            }

        assertContentEquals(ArtifactWriter.moduleSemanticHash(original), hashes[original])
        assertContentEquals(ArtifactWriter.moduleSemanticHash(changed), hashes[changed])
        assertContentEquals(ArtifactWriter.moduleSemanticHash(original), hashes[original.copy()])
        assertEquals(3, calls)
        val another =
            ModuleSemanticHashes {
                calls++
                ArtifactWriter.moduleSemanticHash(it)
            }
        assertContentEquals(ArtifactWriter.moduleSemanticHash(original), another[original])
        assertEquals(4, calls)
    }

    private fun module() = Module(StringId.of(0u), ModuleKind.LIBRARY, listOf(MetadataText.of("library")))
}
