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
import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.EntryPoint
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.Manifest
import ru.lazyhat.compukters.compiler.artifact.model.ModuleId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtifactValidatorTest {
    @Test
    fun `invalid entry produces a stable bounded diagnostic without bytes`() {
        val artifact =
            Artifact(
                manifest = Manifest.minimal(),
                entry = EntryPoint(ModuleId.of(0u), FunctionId.of(0u)),
                modules = emptyList(),
            )

        val errors = validateArtifact(artifact, ArtifactWriteLimits(diagnostics = 1))

        assertEquals(1, errors.size)
        assertEquals(ArtifactWriteErrorCode.BAD_REFERENCE, errors.single().code)
        assertTrue(errors.single().detail.contains("entry module"))
    }

    @Test
    fun `missing block terminator is rejected at its logical location`() {
        val artifact = minimalArtifact(instructions = emptyList())

        val error = validateArtifact(artifact, ArtifactWriteLimits()).first { it.detail.contains("terminator") }

        assertEquals(ArtifactWriteErrorCode.INCONSISTENT_RANGE, error.code)
        assertEquals(0u, error.location?.module)
        assertEquals(0u, error.location?.record)
    }

    @Test
    fun `block and output limits are checked before encoding`() {
        val errors = validateArtifact(minimalArtifact(), ArtifactWriteLimits(blocks = 0, artifactBytes = 100))
        assertTrue(errors.any { it.code == ArtifactWriteErrorCode.LIMIT_EXCEEDED && it.detail.contains("blocks") })
    }
}
