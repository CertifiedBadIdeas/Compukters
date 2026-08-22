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

import ru.lazyhat.compukters.compiler.artifact.model.Artifact
import ru.lazyhat.compukters.compiler.artifact.model.EntryPoint
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.Manifest
import ru.lazyhat.compukters.compiler.artifact.model.MetadataText
import ru.lazyhat.compukters.compiler.artifact.model.ModuleId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArtifactWriterFailureTest {
    @Test
    fun `module semantic identity is deterministic and defensively copied`() {
        val module = languageRuntimeArtifact().modules.single()

        val first = ArtifactWriter.moduleSemanticHash(module)
        val expected = first.copyOf()
        first[0] = (first[0].toInt() xor 0xff).toByte()

        assertContentEquals(expected, ArtifactWriter.moduleSemanticHash(module))
    }

    @Test
    fun `repeated encoding is byte deterministic`() {
        val first = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(languageRuntimeArtifact()))
        val second = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(languageRuntimeArtifact()))

        assertContentEquals(first.bytes, second.bytes)
        assertContentEquals(first.sha256, second.sha256)
    }

    @Test
    fun `non-canonical graph table returns errors and no bytes`() {
        val valid = minimalArtifact()
        val invalidModule = valid.modules.single().copy(strings = listOf(MetadataText.of("app"), MetadataText.of("app")))
        val result = assertIs<ArtifactWriteResult.Failure>(ArtifactWriter.write(valid.copy(modules = listOf(invalidModule))))

        assertEquals(ArtifactWriteErrorCode.NON_CANONICAL_ORDER, result.errors.single().code)
    }

    @Test
    fun `output limit fails before partial artifact publication`() {
        val result =
            assertIs<ArtifactWriteResult.Failure>(
                ArtifactWriter.write(minimalArtifact(), ArtifactWriteLimits(artifactBytes = 100)),
            )

        assertTrue(result.errors.any { it.code == ArtifactWriteErrorCode.LIMIT_EXCEEDED })
    }

    @Test
    fun `diagnostics stop at the configured bound in stable order`() {
        val invalid =
            Artifact(
                manifest = Manifest.minimal(),
                entry = EntryPoint(ModuleId.of(0u), FunctionId.of(0u)),
                modules = emptyList(),
            )

        val result = assertIs<ArtifactWriteResult.Failure>(ArtifactWriter.write(invalid, ArtifactWriteLimits(diagnostics = 1)))

        assertEquals(1, result.errors.size)
        assertEquals(ArtifactWriteErrorCode.BAD_REFERENCE, result.errors.single().code)
    }
}
