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

import ru.lazyhat.compukters.compiler.artifact.analysis.ReferenceLiveness
import ru.lazyhat.compukters.compiler.artifact.model.Constant
import ru.lazyhat.compukters.compiler.artifact.model.ConstantId
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.FunctionValue
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.Manifest
import ru.lazyhat.compukters.compiler.artifact.model.RegisterId
import ru.lazyhat.compukters.compiler.artifact.model.ValueType
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

class RootRangesTest {
    private fun source(repeats: Int) =
        minimalArtifact().let { original ->
            val module = original.modules.single()
            val operations =
                List(repeats) { Instruction.Const(RegisterId.of(0u), ConstantId.of(0u)) } + Instruction.Return(Destination.Unit)
            original.copy(
                manifest = Manifest(0u, 4u, 1u, 1u, 0u, 0u, 64u, 64u, ByteArray(32), ByteArray(32)),
                modules =
                    listOf(
                        module.copy(
                            constants = listOf(Constant.I32(7)),
                            functions = module.functions.map { it.copy(values = listOf(FunctionValue.scalar(ValueType.I32))) },
                            blocks = module.blocks.map { it.copy(instructions = operations) },
                        ),
                    ),
            )
        }

    private fun bytes(repeats: Int): ByteArray {
        val result = ArtifactWriter.write(source(repeats))
        return assertIs<ArtifactWriteResult.Success>(result, result.toString()).bytes
    }

    private fun buffer(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private fun directory(
        bytes: ByteArray,
        kind: Int,
    ) = (0 until buffer(bytes).getInt(16))
        .map { 64 + 32 * it }
        .singleOrNull { buffer(bytes).getShort(it).toInt() and 0xffff == kind }

    private fun reject(
        original: ByteArray,
        mutation: (ByteArray) -> Unit,
    ) {
        val data = original.copyOf().also(mutation)
        MessageDigest.getInstance("SHA-256").digest(data.copyOfRange(0, data.size - 32)).copyInto(data, data.size - 32)
        assertFailsWith<IllegalArgumentException> { ArtifactReader.read(data) }
    }

    @Test
    fun `ranges retain all boundary maps and canonical identity`() {
        val original = ReferenceLiveness.derive(source(20))
        val data = bytes(20)
        val marker = requireNotNull(directory(data, 0x0112))
        assertEquals(1, buffer(data).getShort(marker + 2).toInt())
        val decoded = ArtifactReader.read(data)
        assertEquals(
            original.modules
                .single()
                .functions
                .single()
                .safepointRoots,
            decoded.modules
                .single()
                .functions
                .single()
                .safepointRoots,
        )
        assertContentEquals(
            ArtifactWriter.moduleSemanticHash(original.modules.single()),
            ArtifactWriter.moduleSemanticHash(decoded.modules.single()),
        )
        assertContentEquals(data, assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(decoded)).bytes)
    }

    @Test
    fun `logical byte budget is enforced independently of physical size`() {
        val data = bytes(20)
        val metadata = buffer(data).getLong(requireNotNull(directory(data, 0x0112)) + 8).toInt()
        val length = buffer(data).getLong(metadata + 8).toInt()
        assertEquals(
            21,
            ArtifactReader
                .read(data, length)
                .modules
                .single()
                .functions
                .single()
                .safepointRoots.size,
        )
        assertFailsWith<IllegalArgumentException> { ArtifactReader.read(data, length - 1) }
    }

    @Test
    fun `single boundary keeps legacy roots`() {
        val data = bytes(0)
        assertEquals(null, directory(data, 0x0112))
        assertEquals(
            1,
            ArtifactReader
                .read(data)
                .modules
                .single()
                .functions
                .single()
                .safepointRoots.size,
        )
    }

    @Test
    fun `reader rejects malformed and excessive expansions before constructing maps`() {
        val data = bytes(20)
        val b = buffer(data)
        val marker = requireNotNull(directory(data, 0x0112))
        val metadata = b.getLong(marker + 8).toInt()
        val rootSection = requireNotNull(directory(data, 0x010b))
        val record = b.getLong(rootSection + 8).toInt() + 24
        reject(data) { buffer(it).putShort(marker + 2, 0) }
        reject(data) { buffer(it).putInt(marker + 4, 0) }
        reject(data) { buffer(it).putInt(metadata, 2) }
        reject(data) { buffer(it).putInt(metadata + 4, Int.MAX_VALUE) }
        reject(data) { buffer(it).putLong(metadata + 8, Long.MAX_VALUE) }
        reject(data) { buffer(it).putInt(metadata + 4, 20) }
        reject(data) { buffer(it).putLong(metadata + 8, 24) }
        reject(data) { buffer(it).putInt(record, 1) }
        reject(data) { buffer(it).putInt(record + 4, 1) }
        reject(data) { buffer(it).putInt(record + 8, -1) }
        reject(data) { buffer(it).putInt(record + 12, 0) }
        reject(data) { buffer(it).putInt(record + 12, Int.MAX_VALUE) }
    }
}
