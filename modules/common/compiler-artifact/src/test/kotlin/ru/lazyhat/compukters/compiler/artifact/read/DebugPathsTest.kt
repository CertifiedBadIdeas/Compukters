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

package ru.lazyhat.compukters.compiler.artifact.read

import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.DebugEntry
import ru.lazyhat.compukters.compiler.artifact.model.DebugEntryId
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.MetadataText
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriteLimits
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriteResult
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriter
import ru.lazyhat.compukters.compiler.artifact.write.minimalArtifact
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class DebugPathsTest {
    private fun source(count: Int) =
        minimalArtifact().let { artifact ->
            val module = artifact.modules.single()
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            functions = module.functions.map { it.copy(blockCount = count.toUInt()) },
                            blocks =
                                List(count) { index ->
                                    if (index + 1 == count) {
                                        module.blocks.single()
                                    } else {
                                        module.blocks.single().copy(
                                            instructions = listOf(Instruction.Jump(BlockId.of((index + 1).toUInt()))),
                                        )
                                    }
                                },
                            debug =
                                List(count) { index ->
                                    DebugEntry(
                                        FunctionId.of(0u),
                                        BlockId.of(index.toUInt()),
                                        0u,
                                        12u,
                                        19u,
                                        if (index == 0) null else DebugEntryId.of(0u),
                                        MetadataText.of(if (index % 2 == 0) "src/двигатель.kt" else "src/стабильно.kt"),
                                        (index + 1).toUInt(),
                                        4u,
                                    )
                                },
                        ),
                    ),
            )
        }

    private fun encode(count: Int): ByteArray {
        val result = ArtifactWriter.write(source(count))
        return assertIs<ArtifactWriteResult.Success>(result, result.toString()).bytes
    }

    private fun buffer(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private fun directory(
        bytes: ByteArray,
        kind: Int,
    ): Int? =
        (0 until buffer(bytes).getInt(16))
            .map { 64 + it * 32 }
            .singleOrNull { buffer(bytes).getShort(it).toInt() and 0xffff == kind }

    private fun record(
        bytes: ByteArray,
        kind: Int,
        id: Int = 0,
    ): Int {
        val b = buffer(bytes)
        val payload = b.getLong(requireNotNull(directory(bytes, kind)) + 8).toInt()
        val count = b.getInt(payload)
        return payload + ((16 + (count + 1) * 4 + 7) and -8) + b.getInt(payload + 16 + id * 4)
    }

    private fun reject(
        bytes: ByteArray,
        mutation: (ByteArray) -> Unit,
    ) {
        val malformed = bytes.copyOf().also(mutation)
        MessageDigest
            .getInstance("SHA-256")
            .digest(malformed.copyOfRange(0, malformed.size - 32))
            .copyInto(malformed, malformed.size - 32)
        assertFailsWith<IllegalArgumentException> { ArtifactReader.read(malformed) }
    }

    @Test
    fun `compact paths preserve diagnostics and semantic identity deterministically`() {
        val original = source(12)
        val bytes = encode(12)
        val pool = requireNotNull(directory(bytes, 0x0111))
        assertEquals(1, buffer(bytes).getShort(pool + 2).toInt())
        assertEquals(2, buffer(bytes).getInt(pool + 24))
        val decoded = ArtifactReader.read(bytes)
        assertEquals(original.modules.single().debug, decoded.modules.single().debug)
        assertSame(
            decoded.modules
                .single()
                .debug[0]
                .sourcePath,
            decoded.modules
                .single()
                .debug[2]
                .sourcePath,
        )
        assertContentEquals(bytes, assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(decoded)).bytes)
        val module = original.modules.single()
        assertContentEquals(ArtifactWriter.moduleSemanticHash(module), ArtifactWriter.moduleSemanticHash(module.copy(debug = emptyList())))
        assertIs<ArtifactWriteResult.Failure>(ArtifactWriter.write(original, ArtifactWriteLimits(debugBytes = 1)))
    }

    @Test
    fun `compact output fits limits smaller than repeated legacy paths`() {
        val original = source(12)
        val module = original.modules.single()
        val longPaths =
            original.copy(
                modules =
                    listOf(
                        module.copy(
                            debug = module.debug.map { it.copy(sourcePath = MetadataText.of("src/" + "x".repeat(1024) + ".kt")) },
                        ),
                    ),
            )
        val bytes = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(longPaths)).bytes
        val bounded =
            assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(longPaths, ArtifactWriteLimits(artifactBytes = bytes.size)))
        assertContentEquals(bytes, bounded.bytes)
        assertIs<ArtifactWriteResult.Failure>(ArtifactWriter.write(longPaths, ArtifactWriteLimits(artifactBytes = bytes.size - 1)))
    }

    @Test
    fun `writer rejects noncanonical pooled paths`() {
        val original = source(12)
        val module = original.modules.single()
        val invalid = module.copy(debug = module.debug.map { it.copy(sourcePath = MetadataText.of("../source.kt")) })
        assertIs<ArtifactWriteResult.Failure>(ArtifactWriter.write(original.copy(modules = listOf(invalid))))
    }

    @Test
    fun `small debug tables retain legacy encoding`() {
        val bytes = encode(1)
        assertEquals(null, directory(bytes, 0x0111))
        assertEquals(
            source(1).modules.single().debug,
            ArtifactReader
                .read(bytes)
                .modules
                .single()
                .debug,
        )
    }

    @Test
    fun `reader rejects malformed compact metadata`() {
        val bytes = encode(12)
        val pool = requireNotNull(directory(bytes, 0x0111))
        reject(bytes) { buffer(it).putShort(pool + 2, 0) }
        reject(bytes) { buffer(it).putShort(requireNotNull(directory(it, 0x0110)), 0x8000.toShort()) }
        reject(bytes) { buffer(it).putInt(pool + 4, 0) }
        reject(bytes) { buffer(it).putInt(record(it, 0x0110) + 24, 2) }
        reject(bytes) { buffer(it).putInt(record(it, 0x0110) + 24, 1) }
        reject(bytes) { buffer(it).putInt(record(it, 0x0110) + 20, 0) }
        reject(bytes) {
            val first = record(it, 0x0111)
            val second = record(it, 0x0111, 1)
            it.copyInto(it, second, first, second)
        }
        reject(bytes) {
            for (index in 0 until 12) buffer(it).putInt(record(it, 0x0110, index) + 24, 0)
        }
        reject(bytes) { it[record(it, 0x0111)] = '/'.code.toByte() }
        reject(bytes) { it[record(it, 0x0111)] = 0xff.toByte() }
    }
}
