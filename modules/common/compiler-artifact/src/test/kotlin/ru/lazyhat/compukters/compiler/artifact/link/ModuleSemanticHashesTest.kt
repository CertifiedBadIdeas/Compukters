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

import ru.lazyhat.compukters.compiler.artifact.model.Artifact
import ru.lazyhat.compukters.compiler.artifact.model.EntryPoint
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.Manifest
import ru.lazyhat.compukters.compiler.artifact.model.MetadataText
import ru.lazyhat.compukters.compiler.artifact.model.Module
import ru.lazyhat.compukters.compiler.artifact.model.ModuleId
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

    @Test
    fun `prepared libraries hash once across requests while changed and project modules stay fresh`() {
        val library = module()
        val descriptor = library.copy(kind = ModuleKind.APPLICATION)
        val artifact =
            Artifact(
                manifest = Manifest.minimal(1u),
                entry = EntryPoint(ModuleId.of(0u), FunctionId.of(0u)),
                modules = listOf(descriptor, library, library),
            )
        var preparedCalls = 0
        val prepared =
            PreparedLibraryModules.prepare(listOf(artifact)) {
                preparedCalls++
                ArtifactWriter.moduleSemanticHash(it)
            }
        assertEquals(1, preparedCalls)
        val changed = library.copy(strings = listOf(MetadataText.of("changed")))
        repeat(2) {
            var localCalls = 0
            val hashes =
                ModuleSemanticHashes(prepared) {
                    localCalls++
                    ArtifactWriter.moduleSemanticHash(it)
                }
            val returned = hashes[library]
            returned.fill(0)
            assertContentEquals(ArtifactWriter.moduleSemanticHash(library), hashes[library])
            assertContentEquals(ArtifactWriter.moduleSemanticHash(changed), hashes[changed])
            assertContentEquals(ArtifactWriter.moduleSemanticHash(descriptor), hashes[descriptor])
            assertEquals(2, localCalls)
        }
        assertEquals(1, preparedCalls)
    }

    private fun module() = Module(StringId.of(0u), ModuleKind.LIBRARY, listOf(MetadataText.of("library")))
}
