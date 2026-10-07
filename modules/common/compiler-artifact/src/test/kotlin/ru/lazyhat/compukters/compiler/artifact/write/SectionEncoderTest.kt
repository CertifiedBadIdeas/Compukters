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

import ru.lazyhat.compukters.compiler.artifact.analysis.ReferenceLiveness
import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.DebugEntry
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.MetadataText
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
        assertEquals(
            "a94ff3e025b761c62110a3adbbb5818e788e9d8021fe96f99f0f4e2c0b99325d",
            ArtifactWriter.moduleSemanticHash(minimalArtifact().modules.single()).toHex(),
        )
        assertEquals(32, MessageDigest.getInstance("SHA-256").digest().size)
    }

    @Test
    fun `hash only encoding preserves full encoder hashes and debug failures`() {
        val base = minimalArtifact().modules.single()
        val entry = DebugEntry(FunctionId.of(0u), BlockId.of(0u), 0u, 1u, 2u, null, MetadataText.of("src/программа.kt"), 2u, 4u)
        val debugCases =
            listOf(
                emptyList(),
                List(3) { entry },
                listOf(entry.copy(sourcePath = MetadataText.of("x".repeat(300)))),
                listOf(entry.copy(sourceLine = null, sourceColumn = null)),
                listOf(entry.copy(sourceLine = null)),
                listOf(entry.copy(sourceColumn = null)),
                listOf(entry.copy(sourceLine = 0u)),
                listOf(entry.copy(sourceColumn = 0u)),
            )
        for (debug in debugCases) {
            val module = base.copy(debug = debug)
            val prepared = ReferenceLiveness.derive(module)
            for (maximum in 0..256) {
                val limits = ArtifactWriteLimits(artifactBytes = maximum)
                val full = runCatching { encodeModuleSections(prepared, limits).semanticHash }
                val hash = runCatching { ArtifactWriter.moduleSemanticHash(module, limits) }
                if (full.isSuccess) {
                    assertContentEquals(full.getOrThrow(), hash.getOrThrow())
                } else {
                    val expected = full.exceptionOrNull()!!
                    val actual = hash.exceptionOrNull()!!
                    assertEquals(expected::class, actual::class)
                    assertEquals(expected.message, actual.message)
                    if (expected is ArtifactEncodingException) assertEquals(expected.code, (actual as ArtifactEncodingException).code)
                }
            }
        }
    }
}
