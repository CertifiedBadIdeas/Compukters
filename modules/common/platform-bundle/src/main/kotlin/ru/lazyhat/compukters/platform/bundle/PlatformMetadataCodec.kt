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

package ru.lazyhat.compukters.platform.bundle

import ru.lazyhat.compukters.worker.value.ImmutableBytes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class DecodedPlatformMetadata(
    val module: PlatformModuleId,
    val declarations: List<PlatformDeclaration>,
    val exportedSymbols: List<String>,
    val moduleHeader: ByteArray = ByteArray(0),
    val fragments: List<PlatformMetadataFragment> = emptyList(),
)

class PlatformMetadataFragment(
    val packageName: String,
    val partName: String,
    bytes: ByteArray,
) {
    val bytes: ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is PlatformMetadataFragment &&
            packageName == other.packageName &&
            partName == other.partName &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * (31 * packageName.hashCode() + partName.hashCode()) + bytes.contentHashCode()
}

object PlatformMetadataCodec {
    private const val FORMAT = 6
    private val MAGIC = byteArrayOf('C'.code.toByte(), 'P'.code.toByte(), 'M'.code.toByte(), 'D'.code.toByte())

    fun encode(metadata: DecodedPlatformMetadata): ImmutableBytes {
        val bytes =
            ByteArrayOutputStream().use { output ->
                DataOutputStream(output).use { sink ->
                    sink.write(MAGIC)
                    sink.writeInt(FORMAT)
                    sink.string(metadata.module.namespace)
                    sink.string(metadata.module.name)
                    sink.writeInt(metadata.declarations.size)
                    metadata.declarations.forEach { declaration ->
                        sink.string(declaration.symbol)
                        sink.string(declaration.signature)
                        sink.string(declaration.sourcePath)
                        sink.writeInt(declaration.startUtf16)
                        sink.writeInt(declaration.endUtf16)
                        sink.writeBoolean(declaration.trustedExternal)
                        sink.writeInt(declaration.defaultArguments.size)
                        declaration.defaultArguments.forEach { argument ->
                            when (argument) {
                                PlatformDefaultArgument.ReceiverArraySize -> {
                                    sink.writeByte(3)
                                }

                                null -> {
                                    sink.writeByte(0)
                                }

                                is PlatformDefaultArgument.EnumEntry -> {
                                    sink.writeByte(1)
                                    sink.string(argument.symbol)
                                }

                                is PlatformDefaultArgument.IntValue -> {
                                    sink.writeByte(2)
                                    sink.writeInt(argument.value)
                                }

                                PlatformDefaultArgument.NullValue -> {
                                    sink.writeByte(4)
                                }
                            }
                        }
                    }
                    sink.writeInt(metadata.exportedSymbols.size)
                    metadata.exportedSymbols.forEach(sink::string)
                    sink.bytes(metadata.moduleHeader)
                    sink.writeInt(metadata.fragments.size)
                    metadata.fragments.forEach { fragment ->
                        sink.string(fragment.packageName)
                        sink.string(fragment.partName)
                        sink.bytes(fragment.bytes)
                    }
                }
                output.toByteArray()
            }
        return ImmutableBytes.of(bytes)
    }

    fun decode(bytes: ImmutableBytes): DecodedPlatformMetadata =
        DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use { source ->
            require(source.readNBytes(MAGIC.size).contentEquals(MAGIC)) { "invalid platform metadata magic" }
            require(source.readInt() == FORMAT) { "unsupported platform metadata format" }
            val module = PlatformModuleId(source.string(), source.string())
            val declarations =
                List(source.count("declaration")) {
                    PlatformDeclaration(
                        symbol = source.string(),
                        signature = source.string(),
                        module = module,
                        sourcePath = source.string(),
                        startUtf16 = source.readInt().also { require(it >= 0) },
                        endUtf16 = source.readInt().also { require(it >= 0) },
                        trustedExternal = source.readBoolean(),
                        defaultArguments =
                            List(source.count("default argument")) {
                                when (val tag = source.readUnsignedByte()) {
                                    0 -> null
                                    1 -> PlatformDefaultArgument.EnumEntry(source.string())
                                    2 -> PlatformDefaultArgument.IntValue(source.readInt())
                                    3 -> PlatformDefaultArgument.ReceiverArraySize
                                    4 -> PlatformDefaultArgument.NullValue
                                    else -> throw IllegalArgumentException("invalid platform default argument tag: $tag")
                                }
                            },
                    )
                }
            val exports = List(source.count("export")) { source.string() }
            val moduleHeader = source.bytes("module header")
            val fragments =
                List(source.count("fragment")) {
                    PlatformMetadataFragment(
                        source.string(),
                        source.string(),
                        source.bytes("fragment"),
                    )
                }
            require(source.read() == -1) { "trailing platform metadata bytes" }
            require(declarations == declarations.sortedWith(DECLARATION_ORDER)) { "platform metadata declarations are not canonical" }
            require(exports == exports.distinct().sorted()) { "platform metadata exports are not canonical" }
            require(
                fragments == fragments.sortedWith(compareBy(PlatformMetadataFragment::packageName, PlatformMetadataFragment::partName)),
            ) {
                "platform metadata fragments are not canonical"
            }
            DecodedPlatformMetadata(module, declarations, exports, moduleHeader, fragments)
        }

    private val DECLARATION_ORDER =
        compareBy<PlatformDeclaration>(
            PlatformDeclaration::symbol,
            PlatformDeclaration::signature,
            PlatformDeclaration::sourcePath,
            PlatformDeclaration::startUtf16,
        )
}

private fun DataOutputStream.string(value: String) {
    val bytes = value.encodeToByteArray()
    writeInt(bytes.size)
    write(bytes)
}

private fun DataOutputStream.bytes(value: ByteArray) {
    writeInt(value.size)
    write(value)
}

private fun DataInputStream.string(): String {
    val length = readInt()
    require(length in 0..1_048_576) { "invalid platform metadata string length" }
    return strictUtf8(readNBytes(length), "platform metadata string").also {
        require(it.encodeToByteArray().size == length) { "truncated platform metadata string" }
    }
}

private fun DataInputStream.count(label: String): Int =
    readInt().also { require(it in 0..262_144) { "invalid platform metadata $label count" } }

private fun DataInputStream.bytes(label: String): ByteArray {
    val length = readInt()
    require(length in 0..16_777_216) { "invalid platform metadata $label length" }
    return readNBytes(length).also { require(it.size == length) { "truncated platform metadata $label" } }
}

private fun strictUtf8(
    bytes: ByteArray,
    label: String,
): String =
    try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    } catch (failure: java.nio.charset.CharacterCodingException) {
        throw IllegalArgumentException("$label is not valid UTF-8", failure)
    }
