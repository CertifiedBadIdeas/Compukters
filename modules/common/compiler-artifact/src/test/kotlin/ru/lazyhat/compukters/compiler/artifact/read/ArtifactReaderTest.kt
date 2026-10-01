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
import ru.lazyhat.compukters.compiler.artifact.model.AbiVersion
import ru.lazyhat.compukters.compiler.artifact.model.Block
import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.DebugEntry
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.Manifest
import ru.lazyhat.compukters.compiler.artifact.model.MetadataText
import ru.lazyhat.compukters.compiler.artifact.model.NominalType
import ru.lazyhat.compukters.compiler.artifact.model.PhysicalAtom
import ru.lazyhat.compukters.compiler.artifact.model.PhysicalShape
import ru.lazyhat.compukters.compiler.artifact.model.RegisterId
import ru.lazyhat.compukters.compiler.artifact.model.SemanticFeature
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriteResult
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriter
import ru.lazyhat.compukters.compiler.artifact.write.channelArtifact
import ru.lazyhat.compukters.compiler.artifact.write.languageRuntimeArtifact
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ArtifactReaderTest {
    @Test
    fun `array copy round trips and requires valid operands feature and runtime ABI`() {
        val source = languageRuntimeArtifact()
        val module = source.modules.single()
        val copy = Instruction.ArrayCopy(RegisterId.of(4u), RegisterId.of(4u), RegisterId.of(7u), RegisterId.of(7u), RegisterId.of(7u))
        val blocks =
            module.blocks.mapIndexed { index, block ->
                if (index == 1) block.copy(instructions = block.instructions.dropLast(1) + copy + block.instructions.last()) else block
            }
        val artifact =
            source.copy(
                minimumRuntimeAbi = AbiVersion(1u, 5u),
                semanticFeatures = source.semanticFeatures + SemanticFeature.ARRAY_COPY,
                manifest = Manifest.minimal(maximumBlockCost = 20u),
                modules = listOf(module.copy(blocks = blocks)),
            )
        val encoded = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(artifact)).bytes
        assertContentEquals(encoded, assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(ArtifactReader.read(encoded))).bytes)
        assertIs<ArtifactWriteResult.Failure>(ArtifactWriter.write(artifact.copy(minimumRuntimeAbi = AbiVersion(1u, 4u))))
        assertIs<ArtifactWriteResult.Failure>(ArtifactWriter.write(artifact.copy(semanticFeatures = source.semanticFeatures)))
        for (bad in listOf(copy.copy(source = RegisterId.of(3u)), copy.copy(length = RegisterId.of(4u)))) {
            val invalidBlocks = blocks.map { block -> block.copy(instructions = block.instructions.map { if (it == copy) bad else it }) }
            assertIs<ArtifactWriteResult.Failure>(
                ArtifactWriter.write(artifact.copy(modules = listOf(module.copy(blocks = invalidBlocks)))),
            )
        }
    }

    @Test
    fun `reader rejects malformed source position indices and coordinates`() {
        val source = languageRuntimeArtifact()
        val module = source.modules.single()
        val entry = DebugEntry(FunctionId.of(0u), BlockId.of(0u), 0u, 12u, 19u, null, MetadataText.of("src/main.kt"), 2u, 4u)
        val encoded =
            assertIs<ArtifactWriteResult.Success>(
                ArtifactWriter.write(source.copy(modules = listOf(module.copy(debug = listOf(entry))))),
            ).bytes
        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        val directory = (0 until buffer.getInt(16)).map { 64 + it * 32 }.single { buffer.getShort(it).toInt() and 0xffff == 0x8001 }
        val payload = buffer.getLong(directory + 8).toInt()
        // One indexed record: 16-byte envelope, two offsets; then its 12-byte payload.
        val record = payload + 24
        for ((offset, value) in listOf(record to 1, record + 4 to 0, record + 8 to 0, directory + 4 to 0)) {
            val malformed = encoded.copyOf()
            ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
            val digest = MessageDigest.getInstance("SHA-256").digest(malformed.copyOfRange(0, malformed.size - 32))
            digest.copyInto(malformed, malformed.size - 32)
            assertFailsWith<IllegalArgumentException> { ArtifactReader.read(malformed) }
        }
    }

    @Test
    fun `source positions round trip without changing semantic identity`() {
        val source = languageRuntimeArtifact()
        val module = source.modules.single()
        val entry = DebugEntry(FunctionId.of(0u), BlockId.of(0u), 0u, 12u, 19u, null, MetadataText.of("src/main.kt"), 2u, 4u)
        val mapped = module.copy(debug = listOf(entry))
        assertContentEquals(ArtifactWriter.moduleSemanticHash(module), ArtifactWriter.moduleSemanticHash(mapped))
        val bytes = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(source.copy(modules = listOf(mapped)))).bytes
        assertEquals(
            listOf(entry),
            ArtifactReader
                .read(bytes)
                .modules
                .single()
                .debug,
        )
        assertContentEquals(bytes, assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(ArtifactReader.read(bytes))).bytes)
        val withoutPosition = mapped.copy(debug = listOf(entry.copy(sourceLine = null, sourceColumn = null)))
        val oldBytes = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(source.copy(modules = listOf(withoutPosition)))).bytes
        assertEquals(
            withoutPosition.debug,
            ArtifactReader
                .read(oldBytes)
                .modules
                .single()
                .debug,
        )
        for (invalid in listOf(entry.copy(sourceLine = 0u), entry.copy(sourceColumn = null))) {
            assertIs<ArtifactWriteResult.Failure>(ArtifactWriter.write(source.copy(modules = listOf(mapped.copy(debug = listOf(invalid))))))
        }
    }

    @Test
    fun `round trip preserves physical shapes and exact roots`() {
        val source = languageRuntimeArtifact()
        val module = source.modules.single()
        val function = module.functions.single()
        val shaped =
            function.copy(
                values =
                    function.values.toMutableList().also {
                        it[0] = it[0].copy(physicalShape = PhysicalShape(listOf(PhysicalAtom.I32, PhysicalAtom.REF32)))
                    },
            )
        val shapedModule = module.copy(functions = listOf(shaped))
        val rooted = shaped.copy(safepointRoots = ReferenceLiveness.derive(shapedModule, shaped))
        val artifact = source.copy(modules = listOf(shapedModule.copy(functions = listOf(rooted))))

        val encoded = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(artifact)).bytes
        val decoded = ArtifactReader.read(encoded)

        assertEquals(
            rooted.values,
            decoded.modules
                .single()
                .functions
                .single()
                .values,
        )
        assertEquals(
            rooted.safepointRoots,
            decoded.modules
                .single()
                .functions
                .single()
                .safepointRoots,
        )
    }

    @Test
    fun `writer reader writer round trip preserves canonical bytes`() {
        val encoded = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(languageRuntimeArtifact())).bytes
        val decoded = ArtifactReader.read(encoded)
        val repeatedResult = ArtifactWriter.write(decoded)
        val repeated = assertIs<ArtifactWriteResult.Success>(repeatedResult, repeatedResult.toString()).bytes

        assertContentEquals(encoded, repeated)
    }

    @Test
    fun `channel instructions and manifest limits round trip canonically`() {
        val source = channelArtifact()
        val encoded = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(source)).bytes
        val decoded = ArtifactReader.read(encoded)

        assertEquals(1u, decoded.manifest.maximumChannels)
        assertEquals(1u, decoded.manifest.maximumChannelValues)
        assertEquals(source.modules.single().blocks, decoded.modules.single().blocks)
        val repeated = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(decoded)).bytes
        assertContentEquals(encoded, repeated)
    }

    @Test
    fun `reader rejects corruption and trailing bytes`() {
        val encoded = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(languageRuntimeArtifact())).bytes
        val corrupted = encoded.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }

        assertFailsWith<IllegalArgumentException> { ArtifactReader.read(corrupted) }
        assertFailsWith<IllegalArgumentException> { ArtifactReader.read(encoded + 0) }
    }

    @Test
    fun `class initializer survives writer reader round trip`() {
        val source = languageRuntimeArtifact()
        val module = source.modules.single()
        val initializerId = FunctionId.of(1u)
        val artifact =
            source.copy(
                modules =
                    listOf(
                        module.copy(
                            types =
                                module.types.toMutableList().also { types ->
                                    types[0] = (types[0] as NominalType.Class).copy(initializer = initializerId)
                                },
                            functions =
                                module.functions +
                                    module.functions.single().copy(
                                        owner = TypeRef.Local(TypeId.of(0u)),
                                        firstBlock = BlockId.of(module.blocks.size.toUInt()),
                                        blockCount = 1u,
                                        firstException = module.exceptions.size.toUInt(),
                                        exceptionCount = 0u,
                                    ),
                            blocks =
                                module.blocks +
                                    Block(initializerId, false, listOf(Instruction.Return(Destination.Unit))),
                        ),
                    ),
            )

        val writeResult = ArtifactWriter.write(artifact)
        val encoded = assertIs<ArtifactWriteResult.Success>(writeResult, writeResult.toString()).bytes
        val decoded = ArtifactReader.read(encoded)

        assertEquals(initializerId, (decoded.modules.single().types[0] as NominalType.Class).initializer)
    }
}
