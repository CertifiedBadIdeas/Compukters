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

import ru.lazyhat.compukters.compiler.artifact.model.Constant
import ru.lazyhat.compukters.compiler.artifact.model.DebugEntry
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.ExportVisibility
import ru.lazyhat.compukters.compiler.artifact.model.FunctionFlag
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.Module
import ru.lazyhat.compukters.compiler.artifact.model.NominalType
import ru.lazyhat.compukters.compiler.artifact.model.SymbolKind
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.ValueType
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val STRINGS = 0x0100
internal const val TYPES = 0x0101
internal const val CONSTANTS = 0x0102
internal const val IMPORTS = 0x0103
internal const val EXPORTS = 0x0104
internal const val FIELDS = 0x0105
internal const val FUNCTIONS = 0x0106
internal const val BLOCKS = 0x0107
internal const val CODE = 0x0108
internal const val EXCEPTIONS = 0x0109
internal const val UTF16_LITERALS = 0x010a
internal const val SAFEPOINT_ROOTS = 0x010b
internal const val DEBUG = 0x0110
internal const val DEBUG_PATHS = 0x0111
internal const val SAFEPOINT_ROOT_RANGES = 0x0112
internal const val DEBUG_SOURCE_POSITIONS = 0x8001

internal data class EncodedSection(
    val kind: Int,
    val payload: ByteArray,
    val count: UInt,
)

internal class EncodedModuleSections(
    val semantic: List<EncodedSection>,
    val compactRoots: EncodedSection?,
    val rootRanges: EncodedSection?,
    val debug: EncodedSection?,
    val debugPaths: EncodedSection?,
    val debugPositions: EncodedSection?,
    semanticHash: ByteArray,
) {
    val semanticHash: ByteArray = semanticHash.copyOf()

    fun required(kind: Int): EncodedSection = semantic.single { it.kind == kind }
}

internal fun encodeModuleSections(
    module: Module,
    limits: ArtifactWriteLimits,
): EncodedModuleSections {
    val maximum = limits.artifactBytes
    val semantic = encodeSemanticSections(module, limits)
    var debug: EncodedSection? = null
    var debugPaths: EncodedSection? = null
    if (module.debug.isNotEmpty()) {
        val plan = planDebugPaths(module, maximum)
        if (plan.compact) {
            val paths = plan.paths
            val pathIds = paths.withIndex().associate { it.value to it.index.toUInt() }
            val pool = EncodedSection(DEBUG_PATHS, encodeIndexed(paths.map { it.toByteArray() }, maximum), paths.size.toUInt())
            val compact =
                EncodedSection(
                    DEBUG,
                    encodeIndexed(module.debug.map { encodeDebug(it, maximum, pathIds.getValue(it.sourcePath)) }, maximum),
                    module.debug.size.toUInt(),
                )
            debug = compact
            debugPaths = pool
        } else {
            debug =
                EncodedSection(
                    DEBUG,
                    encodeIndexed(module.debug.map { encodeDebug(it, maximum) }, maximum),
                    module.debug.size.toUInt(),
                )
        }
    }
    val positions =
        module.debug.mapIndexedNotNull { index, entry ->
            validatedSourceLine(entry)?.let { line ->
                BinarySink(maximum)
                    .apply {
                        writeU32(index.toUInt())
                        writeU32(line)
                        writeU32(requireNotNull(entry.sourceColumn))
                    }.toByteArray()
            }
        }
    val debugPositions =
        positions.takeIf { it.isNotEmpty() }?.let {
            EncodedSection(DEBUG_SOURCE_POSITIONS, encodeIndexed(it, maximum), it.size.toUInt())
        }
    val (compactRoots, rootRanges) = encodeRootRanges(module, semantic.single { it.kind == SAFEPOINT_ROOTS }, maximum)
    return EncodedModuleSections(semantic, compactRoots, rootRanges, debug, debugPaths, debugPositions, semanticHash(semantic))
}

private data class RootRun(
    val function: UInt,
    val roots: ru.lazyhat.compukters.compiler.artifact.model.SafepointRoots,
    var count: UInt = 1u,
)

private fun encodeRootRanges(
    module: Module,
    legacy: EncodedSection,
    maximum: Int,
): Pair<EncodedSection?, EncodedSection?> {
    val runs = mutableListOf<RootRun>()
    module.functions.forEachIndexed { function, value ->
        value.safepointRoots.forEach { roots ->
            val previous = runs.lastOrNull()
            if (previous != null && previous.function == function.toUInt() && previous.roots.block == roots.block &&
                previous.roots.instructionBoundary.toULong() + previous.count == roots.instructionBoundary.toULong() &&
                previous.roots.references == roots.references
            ) {
                previous.count++
            } else {
                runs += RootRun(function.toUInt(), roots)
            }
        }
    }
    // Reject a nonprofitable candidate before encoding its larger records.
    val compactSize =
        ((16L + 4L * (runs.size + 1L) + 7L) and -8L) +
            runs.sumOf { 20L + 4L * it.roots.references.size }
    if (((compactSize + 7L) and -8L) + 48 >= checkedAlign8(legacy.payload.size)) return null to null
    val compact =
        EncodedSection(
            SAFEPOINT_ROOTS,
            encodeIndexed(
                runs.map { run ->
                    BinarySink(maximum)
                        .apply {
                            writeU32(run.function)
                            writeU32(run.roots.block.value)
                            writeU32(run.roots.instructionBoundary)
                            writeU32(run.count)
                            writeU16(
                                run.roots.references.size
                                    .toUInt(),
                            )
                            writeU16(0u)
                            run.roots.references.forEach {
                                writeU16(it.value.value.toUInt())
                                writeU16(it.component.toUInt())
                            }
                        }.toByteArray()
                },
                maximum,
            ),
            runs.size.toUInt(),
        )
    val marker =
        EncodedSection(
            SAFEPOINT_ROOT_RANGES,
            BinarySink(16)
                .apply {
                    writeU32(1u)
                    writeU32(legacy.count)
                    writeU64(legacy.payload.size.toULong())
                }.toByteArray(),
            1u,
        )
    return compact to marker
}

internal fun encodeModuleSemanticHash(
    module: Module,
    limits: ArtifactWriteLimits,
): ByteArray {
    val semantic = encodeSemanticSections(module, limits)
    validateDebugEncoding(module, limits.artifactBytes)
    return semanticHash(semantic)
}

private fun encodeSemanticSections(
    module: Module,
    limits: ArtifactWriteLimits,
): List<EncodedSection> {
    val maximum = limits.artifactBytes
    val codeRecords =
        module.blocks.map { block ->
            val sink = BinarySink(limits.codeBytes)
            block.instructions.forEach { sink.writeBytes(encodeInstruction(it, limits.codeBytes).bytes) }
            sink.toByteArray()
        }
    return listOf(
        EncodedSection(STRINGS, encodeIndexed(module.strings.map { it.toByteArray() }, maximum), module.strings.size.toUInt()),
        EncodedSection(TYPES, encodeIndexed(module.types.map { encodeType(it, maximum) }, maximum), module.types.size.toUInt()),
        EncodedSection(
            CONSTANTS,
            encodeIndexed(module.constants.map { encodeConstant(it, maximum) }, maximum),
            module.constants.size.toUInt(),
        ),
        EncodedSection(IMPORTS, encodeIndexed(module.imports.map { encodeImport(it, maximum) }, maximum), module.imports.size.toUInt()),
        EncodedSection(EXPORTS, encodeIndexed(module.exports.map { encodeExport(it, maximum) }, maximum), module.exports.size.toUInt()),
        EncodedSection(FIELDS, encodeIndexed(module.fields.map { encodeField(it, maximum) }, maximum), module.fields.size.toUInt()),
        EncodedSection(
            FUNCTIONS,
            encodeIndexed(module.functions.map { encodeFunction(it, maximum) }, maximum),
            module.functions.size.toUInt(),
        ),
        EncodedSection(
            BLOCKS,
            encodeIndexed(
                module.blocks.mapIndexed { index, block ->
                    val cost =
                        block.instructions
                            .sumOf {
                                instructionFixedCost(
                                    it,
                                    module.functions
                                        .getOrNull(block.owner.value.toInt())
                                        ?.values
                                        .orEmpty(),
                                ).toLong()
                            }.toUInt()
                    val sink = BinarySink(maximum)
                    sink.writeU32(block.owner.value)
                    sink.writeU32(index.toUInt())
                    sink.writeU32(block.instructions.size.toUInt())
                    sink.writeU32(cost)
                    sink.writeU32(if (block.loopHeaderSafepoint) 1u else 0u)
                    sink.writeU32(0u)
                    sink.toByteArray()
                },
                maximum,
            ),
            module.blocks.size.toUInt(),
        ),
        EncodedSection(CODE, encodeIndexed(codeRecords, maximum), codeRecords.size.toUInt()),
        EncodedSection(
            EXCEPTIONS,
            encodeIndexed(
                module.exceptions.map { exception ->
                    BinarySink(maximum)
                        .apply {
                            writeU32(exception.owner.value)
                            writeU32(exception.firstProtectedBlock.value)
                            writeU32(exception.protectedBlockCount)
                            writeU32(exception.catchType?.let(::encodeTypeRef) ?: UInt.MAX_VALUE)
                            writeU32(exception.handlerBlock.value)
                            writeU16(exception.exceptionRegister.value.toUInt())
                            writeU16(0u)
                        }.toByteArray()
                },
                maximum,
            ),
            module.exceptions.size.toUInt(),
        ),
        EncodedSection(
            UTF16_LITERALS,
            encodeIndexed(module.utf16Literals.map { it.toLittleEndianByteArray() }, maximum),
            module.utf16Literals.size.toUInt(),
        ),
        EncodedSection(
            SAFEPOINT_ROOTS,
            encodeIndexed(
                module.functions.flatMapIndexed { functionIndex, function ->
                    function.safepointRoots.map { roots ->
                        encodeSafepointRoots(FunctionId.of(functionIndex.toUInt()), roots, maximum)
                    }
                },
                maximum,
            ),
            module.functions.sumOf { it.safepointRoots.size }.toUInt(),
        ),
    )
}

private data class DebugPathPlan(
    val paths: List<ru.lazyhat.compukters.compiler.artifact.model.MetadataText>,
    val compact: Boolean,
)

// Both the writer and hash-only validator select and bound the same physical representation.
private fun planDebugPaths(
    module: Module,
    maximum: Int,
): DebugPathPlan {
    val paths = module.debug.map(DebugEntry::sourcePath).distinct()
    val pathSizes = paths.associateWith { it.utf8ByteSize }

    fun indexedBytes(
        count: Int,
        bytes: Long,
    ): Long {
        checkIndexedSize(count, bytes, maximum)
        return ((16L + 4L * (count + 1L) + 7L) and -8L) + bytes
    }
    val poolBytes = indexedBytes(paths.size, pathSizes.values.sumOf { it.toLong() })
    val compactBytes = indexedBytes(module.debug.size, 28L * module.debug.size)
    val legacyBytes =
        ((16L + 4L * (module.debug.size + 1L) + 7L) and -8L) +
            module.debug.sumOf { 28L + pathSizes.getValue(it.sourcePath) }
    val compact =
        ((compactBytes + 7L) and -8L) + ((poolBytes + 7L) and -8L) + 32L <
            ((legacyBytes + 7L) and -8L)
    if (compact) {
        require(
            paths.all { path ->
                val text = path.toString()
                text.isNotEmpty() && !text.startsWith('/') && !text.contains('\\') &&
                    text.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
            },
        ) { "debug source path is not canonical" }
    } else {
        indexedBytes(module.debug.size, module.debug.sumOf { 28L + pathSizes.getValue(it.sourcePath) })
    }
    return DebugPathPlan(paths, compact)
}

// Preserve the hash API's encoding checks without materializing non-semantic sections.
private fun validateDebugEncoding(
    module: Module,
    maximum: Int,
) {
    if (module.debug.isEmpty()) return
    planDebugPaths(module, maximum)
    var positionCount = 0
    module.debug.forEach { entry ->
        validatedSourceLine(entry)?.let {
            if (12 > maximum) {
                throw ArtifactEncodingException(ArtifactWriteErrorCode.LIMIT_EXCEEDED, "encoded output exceeds $maximum bytes")
            }
            positionCount++
        }
    }
    if (positionCount > 0) checkIndexedSize(positionCount, positionCount * 12L, maximum)
}

private fun validatedSourceLine(entry: DebugEntry): UInt? {
    require((entry.sourceLine == null) == (entry.sourceColumn == null)) { "source line and column must be paired" }
    entry.sourceLine?.let { line ->
        require(line > 0u && requireNotNull(entry.sourceColumn) > 0u) { "source positions must be one-based" }
    }
    return entry.sourceLine
}

private fun encodeType(
    type: NominalType,
    maximum: Int,
): ByteArray =
    BinarySink(maximum)
        .apply {
            when (type) {
                is NominalType.InlineValue -> {
                    writeU8(4u)
                    writeU8(0u)
                    writeU16(0u)
                    writeU32(type.name.value)
                    writeU16(type.components.size.toUInt())
                    writeU16(0u)
                    type.components.forEach(::writeValueType)
                }

                is NominalType.Class -> {
                    writeU8(0u)
                    writeU8(
                        (if (type.abstract) 1u else 0u) or (if (type.final) 2u else 0u) or
                            (if (type.throwableRoot) 4u else 0u) or ((type.runtimeExceptionKind?.artifactTag ?: 0u) shl 3),
                    )
                    writeU16(type.genericArity.toUInt())
                    writeU32(type.name.value)
                    writeClassLike(type.superType, type.interfaces, type.fieldStart, type.fieldCount, type.methodStart, type.methodCount)
                    type.initializer?.let { writeU32(it.value) }
                }

                is NominalType.Interface -> {
                    writeU8(1u)
                    writeU8(if (type.sealed) 1u else 0u)
                    writeU16(type.genericArity.toUInt())
                    writeU32(type.name.value)
                    writeClassLike(type.superType, type.interfaces, 0u, 0u, type.methodStart, type.methodCount)
                }

                is NominalType.Array -> {
                    writeU8(2u)
                    writeU8((if (type.superType == null) 0u else 1u) or (type.storage.artifactTag shl 1))
                    writeU16(0u)
                    writeU32(type.name.value)
                    writeValueType(type.element)
                    type.superType?.let { writeU32(encodeTypeRef(it)) }
                }

                is NominalType.Function -> {
                    writeU8(3u)
                    writeU8(0u)
                    writeU16(0u)
                    writeU32(type.name.value)
                    writeU16(type.parameters.size.toUInt())
                    writeU16(if (type.suspending) 1u else 0u)
                    writeValueType(type.result)
                    type.parameters.forEach(::writeValueType)
                }
            }
        }.toByteArray()

private fun BinarySink.writeClassLike(
    superType: TypeRef?,
    interfaces: List<TypeRef>,
    fieldStart: UInt,
    fieldCount: UInt,
    methodStart: UInt,
    methodCount: UInt,
) {
    writeU32(superType?.let(::encodeTypeRef) ?: UInt.MAX_VALUE)
    writeU32(interfaces.size.toUInt())
    writeU32(fieldStart)
    writeU32(fieldCount)
    writeU32(methodStart)
    writeU32(methodCount)
    interfaces.forEach { writeU32(encodeTypeRef(it)) }
}

private fun BinarySink.writeValueType(type: ValueType) {
    val kind =
        when (type) {
            ValueType.Unit -> 0u
            ValueType.I32 -> 1u
            ValueType.I64 -> 2u
            ValueType.F32 -> 3u
            ValueType.F64 -> 4u
            ValueType.Bool -> 5u
            ValueType.Char -> 6u
            is ValueType.Ref -> 7u
            is ValueType.Inline -> 8u
        }
    writeU8(kind)
    writeU8(if (type is ValueType.Ref && type.nullable) 1u else 0u)
    writeU16(0u)
    writeU32(
        when (type) {
            is ValueType.Ref -> encodeTypeRef(type.type)
            is ValueType.Inline -> encodeTypeRef(type.type)
            else -> UInt.MAX_VALUE
        },
    )
}

private fun encodeConstant(
    constant: Constant,
    maximum: Int,
): ByteArray =
    BinarySink(maximum)
        .apply {
            writeU8(constant.tag.toUInt())
            when (constant) {
                is Constant.I32 -> writeU32(constant.value.toUInt())
                is Constant.I64 -> writeU64(constant.value.toULong())
                is Constant.F32 -> writeU32(constant.bits)
                is Constant.F64 -> writeU64(constant.bits)
                is Constant.Bool -> writeU8(if (constant.value) 1u else 0u)
                is Constant.Char -> writeU16(constant.codeUnit.toUInt())
                is Constant.StringLiteral -> writeU32(constant.literal.value)
                Constant.Null -> Unit
            }
        }.toByteArray()

private fun encodeImport(
    value: ru.lazyhat.compukters.compiler.artifact.model.Import,
    maximum: Int,
): ByteArray =
    BinarySink(maximum)
        .apply {
            writeU8(value.kind.ordinal.toUInt())
            writeU8(0u)
            writeU16(0u)
            writeU32(value.targetModule.value)
            writeU32(value.targetName.value)
            writeU32(encodeTypeRef(value.expectedSignature))
            require(value.targetModuleHash.size == 32) { "target module hash must contain 32 bytes" }
            writeBytes(value.targetModuleHash)
        }.toByteArray()

private fun encodeExport(
    value: ru.lazyhat.compukters.compiler.artifact.model.Export,
    maximum: Int,
): ByteArray =
    BinarySink(maximum)
        .apply {
            writeU8(value.kind.ordinal.toUInt())
            writeU8(if (value.visibility == ExportVisibility.PUBLIC_LIBRARY) 1u else 0u)
            writeU16(0u)
            writeU32(value.name.value)
            writeU32(value.localSymbol)
            writeU32(encodeTypeRef(value.signature))
        }.toByteArray()

private fun encodeField(
    value: ru.lazyhat.compukters.compiler.artifact.model.Field,
    maximum: Int,
): ByteArray =
    BinarySink(maximum)
        .apply {
            writeU32(encodeTypeRef(value.owner))
            writeU32(value.name.value)
            writeValueType(value.type)
            writeU32((if (value.mutable) 1u else 0u) or (if (value.static) 2u else 0u))
            writeU32(0u)
        }.toByteArray()

private fun encodeFunction(
    value: ru.lazyhat.compukters.compiler.artifact.model.Function,
    maximum: Int,
): ByteArray =
    BinarySink(maximum)
        .apply {
            writeU32(value.owner?.let(::encodeTypeRef) ?: UInt.MAX_VALUE)
            writeU32(value.name.value)
            writeU32(encodeTypeRef(value.signature))
            writeU32(
                (if (FunctionFlag.SUSPENDING in value.flags) 1u else 0u) or
                    (if (FunctionFlag.STATIC in value.flags) 2u else 0u) or
                    (if (FunctionFlag.VIRTUAL in value.flags) 4u else 0u) or
                    (if (FunctionFlag.ABSTRACT in value.flags) 8u else 0u),
            )
            writeU16(value.values.size.toUInt())
            writeU16(value.parameterCount)
            writeU32(value.firstBlock.value)
            writeU32(value.blockCount)
            writeU32(value.firstException)
            writeU32(value.exceptionCount)
            value.values.forEach { functionValue ->
                writeValueType(functionValue.semanticType)
                writeU16(
                    functionValue.physicalShape.components.size
                        .toUInt(),
                )
                writeU16(0u)
                functionValue.physicalShape.components.forEach { writeU8(it.artifactTag) }
            }
        }.toByteArray()

private fun encodeSafepointRoots(
    function: FunctionId,
    roots: ru.lazyhat.compukters.compiler.artifact.model.SafepointRoots,
    maximum: Int,
): ByteArray =
    BinarySink(maximum)
        .apply {
            writeU32(function.value)
            writeU32(roots.block.value)
            writeU32(roots.instructionBoundary)
            writeU16(roots.references.size.toUInt())
            writeU16(0u)
            roots.references.forEach { reference ->
                writeU16(reference.value.value.toUInt())
                writeU16(reference.component.toUInt())
            }
        }.toByteArray()

private fun encodeDebug(
    value: DebugEntry,
    maximum: Int,
    pathId: UInt? = null,
): ByteArray =
    BinarySink(maximum)
        .apply {
            writeU32(value.function.value)
            writeU32(value.block.value)
            writeU32(value.instruction)
            writeU32(value.startUtf16)
            writeU32(value.endUtf16)
            writeU32(value.inlineParent?.value ?: UInt.MAX_VALUE)
            if (pathId != null) {
                writeU32(pathId)
            } else {
                val path = value.sourcePath.toByteArray()
                writeU32(path.size.toUInt())
                writeBytes(path)
            }
        }.toByteArray()

private fun semanticHash(sections: List<EncodedSection>): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("Compukter module v2\u0000".toByteArray(StandardCharsets.US_ASCII))
    sections.sortedBy(EncodedSection::kind).forEach { section ->
        digest.update(BinarySink(2).apply { writeU16(section.kind.toUInt()) }.toByteArray())
        digest.update(BinarySink(8).apply { writeU64(section.payload.size.toULong()) }.toByteArray())
        digest.update(section.payload)
    }
    return digest.digest()
}
