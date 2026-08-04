/*
 * The Compukter Kraft Developers
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

package ru.lazyhat.compukterkraft.core.device.display.retained

import java.util.TreeMap

sealed interface RetainedDisplayResource

class RetainedImageRgb565 internal constructor(
    val width: Int,
    val height: Int,
    private val pixels: ShortArray,
) : RetainedDisplayResource {
    fun pixelAt(
        x: Int,
        y: Int,
    ): Int {
        require(x in 0 until width && y in 0 until height)
        return pixels[y * width + x].toInt() and 0xffff
    }

    internal fun copyPixels(): ShortArray = pixels.copyOf()

    override fun equals(other: Any?): Boolean =
        other is RetainedImageRgb565 && width == other.width && height == other.height && pixels.contentEquals(other.pixels)

    override fun hashCode(): Int = 31 * (31 * width + height) + pixels.contentHashCode()
}

class RetainedMask1Bpp internal constructor(
    val width: Int,
    val height: Int,
    private val rows: ByteArray,
) : RetainedDisplayResource {
    fun bitAt(
        x: Int,
        y: Int,
    ): Boolean {
        require(x in 0 until width && y in 0 until height)
        val rowBytes = (width + 7) / 8
        return rows[y * rowBytes + x / 8].toInt() and (0x80 ushr (x % 8)) != 0
    }

    internal fun copyRows(): ByteArray = rows.copyOf()

    override fun equals(other: Any?): Boolean =
        other is RetainedMask1Bpp && width == other.width && height == other.height && rows.contentEquals(other.rows)

    override fun hashCode(): Int = 31 * (31 * width + height) + rows.contentHashCode()
}

data class RetainedMaskInstance(
    val sourceX: Int,
    val sourceY: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val destinationX: Int,
    val destinationY: Int,
    val destinationWidth: Int,
    val destinationHeight: Int,
    val foregroundRgb565: Int,
    val backgroundRgb565: Int,
    val opaqueBackground: Boolean,
)

class RetainedMaskInstanceBuffer internal constructor(
    val capacity: Int,
    instances: List<RetainedMaskInstance>,
) : RetainedDisplayResource {
    val instances: List<RetainedMaskInstance> = instances.toList()

    override fun equals(other: Any?): Boolean =
        other is RetainedMaskInstanceBuffer && capacity == other.capacity && instances == other.instances

    override fun hashCode(): Int = 31 * capacity + instances.hashCode()
}

data class RetainedDisplayResourceEntry(
    val resourceId: UInt,
    val localIdentity: Long,
    val content: RetainedDisplayResource,
)

data class RetainedResourceBinding(
    val resourceId: UInt,
    val localIdentity: Long,
)

data class RetainedSourceRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

data class RetainedDestinationRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

sealed interface RetainedDrawCommand {
    data class PushClip(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    ) : RetainedDrawCommand

    data object PopClip : RetainedDrawCommand

    data class FillRect(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val rgb565: Int,
    ) : RetainedDrawCommand

    data class DrawImage(
        val image: RetainedResourceBinding,
        val source: RetainedSourceRect,
        val destination: RetainedDestinationRect,
    ) : RetainedDrawCommand

    data class DrawMask(
        val mask: RetainedResourceBinding,
        val source: RetainedSourceRect,
        val destination: RetainedDestinationRect,
        val foregroundRgb565: Int,
        val backgroundRgb565: Int,
        val opaqueBackground: Boolean,
    ) : RetainedDrawCommand

    data class DrawMaskInstances(
        val mask: RetainedResourceBinding,
        val instances: RetainedResourceBinding,
        val firstInstance: Int,
        val instanceCount: Int,
        val translationX: Int,
        val translationY: Int,
    ) : RetainedDrawCommand
}

class RetainedDrawList internal constructor(
    val backgroundRgb565: Int,
    commands: List<RetainedDrawCommand>,
) {
    val commands: List<RetainedDrawCommand> = commands.toList()
}

class RetainedDisplayState internal constructor(
    val computerId: UInt,
    val viewerEpoch: ULong,
    val sequence: ULong,
    resources: List<RetainedDisplayResourceEntry>,
    val drawList: RetainedDrawList,
) {
    val resources: List<RetainedDisplayResourceEntry> = resources.toList()
    private val resourcesById = this.resources.associateBy { it.resourceId }

    fun resource(resourceId: UInt): RetainedDisplayResourceEntry? = resourcesById[resourceId]
}

sealed interface RetainedDisplayApplyResult {
    data class Installed(
        val state: RetainedDisplayState,
        val acknowledgement: ByteArray,
        val damage: RetainedDisplayInstallDamage,
    ) : RetainedDisplayApplyResult

    data class ResyncRequired(
        val reason: RetainedDisplayResyncReason,
        val request: ByteArray?,
    ) : RetainedDisplayApplyResult
}

class RetainedDisplayReplica {
    var state: RetainedDisplayState? = null
        private set
    private var nextLocalIdentity = 1L

    fun apply(payload: ByteArray): RetainedDisplayApplyResult {
        val previous = state
        var header: NetworkHeader? = null
        return try {
            val reader = LeReader(payload)
            header = readHeader(reader)
            val staged =
                when (header.kind) {
                    RetainedDisplayProtocol.SNAPSHOT_KIND -> decodeSnapshot(reader, header, nextLocalIdentity)
                    RetainedDisplayProtocol.DELTA_KIND -> decodeDelta(reader, header, previous, nextLocalIdentity)
                    else -> validationFailure()
                }
            state = staged.state
            nextLocalIdentity = staged.nextLocalIdentity
            RetainedDisplayApplyResult.Installed(
                staged.state,
                RetainedDisplayProtocol.encodeAcknowledgement(
                    staged.state.computerId,
                    staged.state.viewerEpoch,
                    staged.state.sequence,
                ),
                staged.damage,
            )
        } catch (failure: ReplicaValidationFailure) {
            RetainedDisplayApplyResult.ResyncRequired(
                failure.reason,
                resyncRequest(previous, header, failure.reason),
            )
        }
    }

    fun clearAndRequestResync(reason: RetainedDisplayResyncReason): ByteArray? {
        val current = state ?: return null
        state = null
        return RetainedDisplayProtocol.encodeResyncRequest(
            current.computerId,
            current.viewerEpoch,
            current.sequence,
            reason,
        )
    }

    private fun resyncRequest(
        previous: RetainedDisplayState?,
        header: NetworkHeader?,
        reason: RetainedDisplayResyncReason,
    ): ByteArray? {
        val identity =
            when {
                header?.kind == RetainedDisplayProtocol.SNAPSHOT_KIND -> {
                    header.computerId to header.viewerEpoch
                }

                previous != null -> {
                    previous.computerId to previous.viewerEpoch
                }

                header != null -> {
                    header.computerId to header.viewerEpoch
                }

                else -> {
                    return null
                }
            }
        val currentSequence =
            previous
                ?.takeIf { it.computerId == identity.first && it.viewerEpoch == identity.second }
                ?.sequence
        return RetainedDisplayProtocol.encodeResyncRequest(
            identity.first,
            identity.second,
            currentSequence,
            reason,
        )
    }
}

private const val MAX_RESOURCES = 128
private const val MAX_RESOURCE_BYTES = 131_072
private const val MAX_TOTAL_RESOURCE_BYTES = 262_144
private const val MAX_DRAW_LIST_BYTES = 65_536
private const val MAX_DRAW_COMMANDS = 2_048
private const val MAX_CLIP_DEPTH = 32
private const val MAX_RESOURCE_CHANGES = 256
private const val MAX_PATCHES = 2_048
private const val INSTANCE_BYTES = 24

private data class NetworkHeader(
    val kind: Int,
    val computerId: UInt,
    val viewerEpoch: ULong,
)

private data class StagedReplica(
    val state: RetainedDisplayState,
    val nextLocalIdentity: Long,
    val damage: RetainedDisplayInstallDamage,
)

private class ReplicaValidationFailure(
    val reason: RetainedDisplayResyncReason,
) : RuntimeException(null, null, false, false)

private fun validationFailure(): Nothing = throw ReplicaValidationFailure(RetainedDisplayResyncReason.MESSAGE_VALIDATION_FAILED)

private fun baseMismatch(): Nothing = throw ReplicaValidationFailure(RetainedDisplayResyncReason.BASE_MISMATCH)

private fun replicaStateLost(): Nothing = throw ReplicaValidationFailure(RetainedDisplayResyncReason.REPLICA_STATE_LOST)

private fun readHeader(reader: LeReader): NetworkHeader {
    if (reader.size !in RetainedDisplayProtocol.HEADER_BYTES..RetainedDisplayProtocol.MAX_MESSAGE_BYTES) {
        validationFailure()
    }
    if (reader.readU32() != RetainedDisplayProtocol.MAGIC || reader.readU16() != RetainedDisplayProtocol.VERSION) {
        validationFailure()
    }
    val kind = reader.readU16()
    if (reader.readU32().toLong() != reader.size.toLong()) validationFailure()
    val computerId = reader.readU32()
    val viewerEpoch = reader.readU64()
    if (computerId == 0u || viewerEpoch == 0uL) validationFailure()
    return NetworkHeader(kind, computerId, viewerEpoch)
}

private fun decodeSnapshot(
    reader: LeReader,
    header: NetworkHeader,
    initialLocalIdentity: Long,
): StagedReplica {
    val sequence = reader.readU64()
    val resourceCount = reader.readCount(MAX_RESOURCES)
    val drawListBytes = reader.readLength(MAX_DRAW_LIST_BYTES)
    if (drawListBytes < 8) validationFailure()
    val resources = ArrayList<RetainedDisplayResourceEntry>(resourceCount)
    var nextIdentity = initialLocalIdentity
    var previousId = 0u
    var totalPayloadBytes = 0
    repeat(resourceCount) { index ->
        val parsed = readFullResource(reader, allocateIdentity(nextIdentity))
        nextIdentity = incrementIdentity(nextIdentity)
        if (index > 0 && parsed.resourceId <= previousId) validationFailure()
        previousId = parsed.resourceId
        totalPayloadBytes = checkedTotalPayload(totalPayloadBytes, parsed.content)
        resources += parsed
    }
    if (reader.remaining != drawListBytes) validationFailure()
    val byId = resources.associateBy { it.resourceId }
    val drawList = readDrawList(reader, drawListBytes, byId)
    reader.requireEnd()
    return StagedReplica(
        RetainedDisplayState(header.computerId, header.viewerEpoch, sequence, resources, drawList),
        nextIdentity,
        RetainedDisplayInstallDamage.FullReplacement,
    )
}

private fun decodeDelta(
    reader: LeReader,
    header: NetworkHeader,
    previous: RetainedDisplayState?,
    initialLocalIdentity: Long,
): StagedReplica {
    val baseSequence = reader.readU64()
    val targetSequence = reader.readU64()
    if (targetSequence <= baseSequence) validationFailure()
    val changeCount = reader.readCount(MAX_RESOURCE_CHANGES)
    val drawListBytes = reader.readLength(MAX_DRAW_LIST_BYTES)
    if (drawListBytes != 0 && drawListBytes < 8) validationFailure()
    val installed = previous ?: replicaStateLost()
    if (
        installed.computerId != header.computerId ||
        installed.viewerEpoch != header.viewerEpoch ||
        installed.sequence != baseSequence
    ) {
        baseMismatch()
    }
    val resources = TreeMap<UInt, RetainedDisplayResourceEntry>()
    installed.resources.forEach { resources[it.resourceId] = it }
    var nextIdentity = initialLocalIdentity
    var previousChangeId = 0u
    var previousWasDrop = false
    var patchCount = 0
    val resourceChanges = ArrayList<RetainedResourceDamage>(changeCount)
    repeat(changeCount) { index ->
        val opcode = reader.peekU16()
        val result =
            when (opcode) {
                0x0001, 0x0002, 0x0003 -> {
                    val created = readFullResource(reader, allocateIdentity(nextIdentity))
                    nextIdentity = incrementIdentity(nextIdentity)
                    if (created.content.kindOpcode() != opcode) validationFailure()
                    if (resources.putIfAbsent(created.resourceId, created) != null) validationFailure()
                    ChangeResult(
                        created.resourceId,
                        false,
                        0,
                        RetainedResourceDamage.Created(created.resourceId, created.localIdentity),
                    )
                }

                0x0010 -> {
                    patchImage(reader, resources)
                }

                0x0011 -> {
                    patchMask(reader, resources)
                }

                0x0012 -> {
                    patchInstances(reader, resources)
                }

                0x0020 -> {
                    dropResource(reader, resources)
                }

                else -> {
                    validationFailure()
                }
            }
        if (index > 0) {
            if (result.resourceId < previousChangeId) validationFailure()
            if (result.resourceId == previousChangeId && !(previousWasDrop && opcode in 0x0001..0x0003)) {
                validationFailure()
            }
        }
        previousChangeId = result.resourceId
        previousWasDrop = result.wasDrop
        patchCount = checkedPatchCount(patchCount, result.patchCount)
        resourceChanges += result.damage
    }
    if (reader.remaining != drawListBytes) validationFailure()
    if (resources.size > MAX_RESOURCES) validationFailure()
    var totalPayloadBytes = 0
    resources.values.forEach { totalPayloadBytes = checkedTotalPayload(totalPayloadBytes, it.content) }
    val drawList =
        if (drawListBytes == 0) {
            validateResolvedDrawList(installed.drawList, resources)
            installed.drawList
        } else {
            readDrawList(reader, drawListBytes, resources)
        }
    reader.requireEnd()
    return StagedReplica(
        RetainedDisplayState(
            header.computerId,
            header.viewerEpoch,
            targetSequence,
            resources.values.toList(),
            drawList,
        ),
        nextIdentity,
        RetainedDisplayInstallDamage.Delta(resourceChanges, drawListBytes != 0),
    )
}

private data class ChangeResult(
    val resourceId: UInt,
    val wasDrop: Boolean,
    val patchCount: Int,
    val damage: RetainedResourceDamage,
)

private fun readFullResource(
    reader: LeReader,
    localIdentity: Long,
): RetainedDisplayResourceEntry {
    val record = reader.readRecord(minimumBytes = 16)
    val kind = record.readU16()
    if (record.readU16() != 0) validationFailure()
    val declaredLength = record.readU32().toLong()
    if (declaredLength != record.size.toLong()) validationFailure()
    val resourceId = record.readU32()
    if (resourceId == 0u) validationFailure()
    val content =
        when (kind) {
            1 -> readImage(record)
            2 -> readMask(record)
            3 -> readInstanceBuffer(record)
            else -> validationFailure()
        }
    record.requireEnd()
    if (content.payloadBytes() > MAX_RESOURCE_BYTES) validationFailure()
    return RetainedDisplayResourceEntry(resourceId, localIdentity, content)
}

private fun readImage(reader: LeReader): RetainedImageRgb565 {
    val width = reader.readU16()
    val height = reader.readU16()
    val area = checkedArea(width, height)
    if (reader.remaining != area * 2) validationFailure()
    val pixels = ShortArray(area) { reader.readU16().toShort() }
    return RetainedImageRgb565(width, height, pixels)
}

private fun readMask(reader: LeReader): RetainedMask1Bpp {
    val width = reader.readU16()
    val height = reader.readU16()
    val rowBytes = checkedMaskRowBytes(width)
    if (height == 0 || reader.remaining != rowBytes * height) validationFailure()
    val rows = reader.readBytes(reader.remaining)
    validateMaskPadding(width, height, rows)
    return RetainedMask1Bpp(width, height, rows)
}

private fun readInstanceBuffer(reader: LeReader): RetainedMaskInstanceBuffer {
    val capacity = reader.readU16()
    if (capacity == 0 || reader.readU16() != 0 || reader.remaining != capacity * INSTANCE_BYTES) {
        validationFailure()
    }
    return RetainedMaskInstanceBuffer(capacity, List(capacity) { readInstance(reader) })
}

private fun readInstance(reader: LeReader): RetainedMaskInstance {
    val sourceX = reader.readU16()
    val sourceY = reader.readU16()
    val sourceWidth = reader.readU16()
    val sourceHeight = reader.readU16()
    val destinationX = reader.readI16()
    val destinationY = reader.readI16()
    val destinationWidth = reader.readU16()
    val destinationHeight = reader.readU16()
    val foreground = reader.readU16()
    val background = reader.readU16()
    val flags = reader.readU16()
    val reserved = reader.readU16()
    if (
        sourceWidth == 0 || sourceHeight == 0 || destinationWidth == 0 || destinationHeight == 0 ||
        flags and 1.inv() != 0 || reserved != 0 || (flags and 1 == 0 && background != 0)
    ) {
        validationFailure()
    }
    return RetainedMaskInstance(
        sourceX,
        sourceY,
        sourceWidth,
        sourceHeight,
        destinationX,
        destinationY,
        destinationWidth,
        destinationHeight,
        foreground,
        background,
        flags and 1 != 0,
    )
}

private fun patchImage(
    reader: LeReader,
    resources: MutableMap<UInt, RetainedDisplayResourceEntry>,
): ChangeResult {
    val record = readPatchRecord(reader, 0x0010)
    val entry = resources[record.resourceId] ?: validationFailure()
    val image = entry.content as? RetainedImageRgb565 ?: validationFailure()
    val pixels = image.copyPixels()
    val rectangles = ArrayList<RetainedPatchRectangle>(record.patchCount)
    repeat(record.patchCount) {
        val rectangle = readPatchRectangle(record.reader, image.width, image.height)
        rectangles += rectangle.toPublicRectangle()
        for (row in 0 until rectangle.height) {
            val destination = (rectangle.y + row) * image.width + rectangle.x
            repeat(rectangle.width) { column -> pixels[destination + column] = record.reader.readU16().toShort() }
        }
        record.reader.align4AndRequireZeroPadding()
    }
    record.reader.requireEnd()
    resources[record.resourceId] = entry.copy(content = RetainedImageRgb565(image.width, image.height, pixels))
    return ChangeResult(
        record.resourceId,
        false,
        record.patchCount,
        RetainedResourceDamage.ImagePatched(record.resourceId, entry.localIdentity, rectangles),
    )
}

private fun patchMask(
    reader: LeReader,
    resources: MutableMap<UInt, RetainedDisplayResourceEntry>,
): ChangeResult {
    val record = readPatchRecord(reader, 0x0011)
    val entry = resources[record.resourceId] ?: validationFailure()
    val mask = entry.content as? RetainedMask1Bpp ?: validationFailure()
    val rows = mask.copyRows()
    val destinationRowBytes = checkedMaskRowBytes(mask.width)
    val rectangles = ArrayList<RetainedPatchRectangle>(record.patchCount)
    repeat(record.patchCount) {
        val rectangle = readPatchRectangle(record.reader, mask.width, mask.height)
        rectangles += rectangle.toPublicRectangle()
        val patchRowBytes = checkedMaskRowBytes(rectangle.width)
        val patchRows = record.reader.readBytes(patchRowBytes * rectangle.height)
        validateMaskPadding(rectangle.width, rectangle.height, patchRows)
        for (row in 0 until rectangle.height) {
            for (column in 0 until rectangle.width) {
                val sourceSet = patchRows[row * patchRowBytes + column / 8].toInt() and (0x80 ushr (column % 8)) != 0
                val destinationColumn = rectangle.x + column
                val destinationIndex = (rectangle.y + row) * destinationRowBytes + destinationColumn / 8
                val destinationBit = 0x80 ushr (destinationColumn % 8)
                rows[destinationIndex] =
                    if (sourceSet) {
                        (rows[destinationIndex].toInt() or destinationBit).toByte()
                    } else {
                        (rows[destinationIndex].toInt() and destinationBit.inv()).toByte()
                    }
            }
        }
        record.reader.align4AndRequireZeroPadding()
    }
    record.reader.requireEnd()
    resources[record.resourceId] = entry.copy(content = RetainedMask1Bpp(mask.width, mask.height, rows))
    return ChangeResult(
        record.resourceId,
        false,
        record.patchCount,
        RetainedResourceDamage.MaskPatched(record.resourceId, entry.localIdentity, rectangles),
    )
}

private fun patchInstances(
    reader: LeReader,
    resources: MutableMap<UInt, RetainedDisplayResourceEntry>,
): ChangeResult {
    val record = readPatchRecord(reader, 0x0012)
    val entry = resources[record.resourceId] ?: validationFailure()
    val buffer = entry.content as? RetainedMaskInstanceBuffer ?: validationFailure()
    val instances = buffer.instances.toMutableList()
    val ranges = ArrayList<RetainedPatchRange>(record.patchCount)
    repeat(record.patchCount) {
        val start = record.reader.readU16()
        val count = record.reader.readU16()
        if (count == 0 || start + count > buffer.capacity) validationFailure()
        ranges += RetainedPatchRange(start, count)
        repeat(count) { offset -> instances[start + offset] = readInstance(record.reader) }
    }
    record.reader.requireEnd()
    resources[record.resourceId] = entry.copy(content = RetainedMaskInstanceBuffer(buffer.capacity, instances))
    return ChangeResult(
        record.resourceId,
        false,
        record.patchCount,
        RetainedResourceDamage.InstancesPatched(record.resourceId, entry.localIdentity, ranges),
    )
}

private data class PatchRecord(
    val reader: LeReader,
    val resourceId: UInt,
    val patchCount: Int,
)

private fun readPatchRecord(
    reader: LeReader,
    expectedOpcode: Int,
): PatchRecord {
    val record = reader.readRecord(minimumBytes = 16)
    if (record.readU16() != expectedOpcode || record.readU16() != 0 || record.readU32().toLong() != record.size.toLong()) {
        validationFailure()
    }
    val resourceId = record.readU32()
    val patchCount = record.readCount(MAX_PATCHES)
    if (resourceId == 0u || patchCount == 0) validationFailure()
    return PatchRecord(record, resourceId, patchCount)
}

private fun dropResource(
    reader: LeReader,
    resources: MutableMap<UInt, RetainedDisplayResourceEntry>,
): ChangeResult {
    val record = reader.readRecord(minimumBytes = 12)
    if (record.size != 12 || record.readU16() != 0x0020 || record.readU16() != 0 || record.readU32() != 12u) {
        validationFailure()
    }
    val resourceId = record.readU32()
    record.requireEnd()
    if (resourceId == 0u) validationFailure()
    val dropped = resources.remove(resourceId) ?: validationFailure()
    return ChangeResult(
        resourceId,
        true,
        0,
        RetainedResourceDamage.Dropped(resourceId, dropped.localIdentity),
    )
}

private data class PatchRectangle(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

private fun PatchRectangle.toPublicRectangle(): RetainedPatchRectangle = RetainedPatchRectangle(x, y, width, height)

private fun readPatchRectangle(
    reader: LeReader,
    resourceWidth: Int,
    resourceHeight: Int,
): PatchRectangle {
    val rectangle = PatchRectangle(reader.readU16(), reader.readU16(), reader.readU16(), reader.readU16())
    if (
        rectangle.width == 0 || rectangle.height == 0 ||
        rectangle.x + rectangle.width > resourceWidth || rectangle.y + rectangle.height > resourceHeight
    ) {
        validationFailure()
    }
    return rectangle
}

private fun readDrawList(
    reader: LeReader,
    byteLength: Int,
    resources: Map<UInt, RetainedDisplayResourceEntry>,
): RetainedDrawList {
    val drawReader = reader.readSlice(byteLength)
    val background = drawReader.readU16()
    if (drawReader.readU16() != 0) validationFailure()
    val commandCount = drawReader.readCount(MAX_DRAW_COMMANDS)
    val commands = ArrayList<RetainedDrawCommand>(commandCount)
    var clipDepth = 0
    repeat(commandCount) {
        val commandReader = drawReader.readRecord(minimumBytes = 8)
        val opcode = commandReader.readU16()
        val flags = commandReader.readU16()
        if (commandReader.readU32().toLong() != commandReader.size.toLong()) validationFailure()
        val command =
            when (opcode) {
                0x0001 -> {
                    requireCommandEnvelope(commandReader, flags, 16)
                    val command =
                        RetainedDrawCommand.PushClip(
                            commandReader.readI16(),
                            commandReader.readI16(),
                            commandReader.readU16(),
                            commandReader.readU16(),
                        )
                    requireExtent(command.width, command.height)
                    if (clipDepth == MAX_CLIP_DEPTH) validationFailure()
                    clipDepth += 1
                    command
                }

                0x0002 -> {
                    requireCommandEnvelope(commandReader, flags, 8)
                    if (clipDepth == 0) validationFailure()
                    clipDepth -= 1
                    RetainedDrawCommand.PopClip
                }

                0x0010 -> {
                    requireCommandEnvelope(commandReader, flags, 20)
                    val command =
                        RetainedDrawCommand.FillRect(
                            commandReader.readI16(),
                            commandReader.readI16(),
                            commandReader.readU16(),
                            commandReader.readU16(),
                            commandReader.readU16(),
                        )
                    if (commandReader.readU16() != 0) validationFailure()
                    requireExtent(command.width, command.height)
                    command
                }

                0x0020 -> {
                    requireCommandEnvelope(commandReader, flags, 28)
                    val entry = requireResource(resources, commandReader.readU32(), RetainedImageRgb565::class.java)
                    val source = readSource(commandReader)
                    val destination = readDestination(commandReader)
                    validateImageBinding(entry, source, destination)
                    RetainedDrawCommand.DrawImage(entry.binding(), source, destination)
                }

                0x0021 -> {
                    if (flags and 1.inv() != 0) validationFailure()
                    requireCommandLength(commandReader, 32)
                    val entry = requireResource(resources, commandReader.readU32(), RetainedMask1Bpp::class.java)
                    val source = readSource(commandReader)
                    val destination = readDestination(commandReader)
                    val foreground = commandReader.readU16()
                    val maskBackground = commandReader.readU16()
                    if (flags and 1 == 0 && maskBackground != 0) validationFailure()
                    validateMaskBinding(entry, source, destination)
                    RetainedDrawCommand.DrawMask(
                        entry.binding(),
                        source,
                        destination,
                        foreground,
                        maskBackground,
                        flags and 1 != 0,
                    )
                }

                0x0022 -> {
                    requireCommandEnvelope(commandReader, flags, 24)
                    val mask = requireResource(resources, commandReader.readU32(), RetainedMask1Bpp::class.java)
                    val instances =
                        requireResource(resources, commandReader.readU32(), RetainedMaskInstanceBuffer::class.java)
                    val first = commandReader.readU16()
                    val count = commandReader.readU16()
                    val translationX = commandReader.readI16()
                    val translationY = commandReader.readI16()
                    validateInstanceBinding(mask, instances, first, count)
                    RetainedDrawCommand.DrawMaskInstances(
                        mask.binding(),
                        instances.binding(),
                        first,
                        count,
                        translationX,
                        translationY,
                    )
                }

                else -> {
                    validationFailure()
                }
            }
        commandReader.requireEnd()
        commands += command
    }
    if (clipDepth != 0) validationFailure()
    drawReader.requireEnd()
    return RetainedDrawList(background, commands)
}

private fun validateResolvedDrawList(
    drawList: RetainedDrawList,
    resources: Map<UInt, RetainedDisplayResourceEntry>,
) {
    for (command in drawList.commands) {
        when (command) {
            is RetainedDrawCommand.DrawImage -> {
                val entry = requireBinding(resources, command.image, RetainedImageRgb565::class.java)
                validateImageBinding(entry, command.source, command.destination)
            }

            is RetainedDrawCommand.DrawMask -> {
                val entry = requireBinding(resources, command.mask, RetainedMask1Bpp::class.java)
                if (!command.opaqueBackground && command.backgroundRgb565 != 0) validationFailure()
                validateMaskBinding(entry, command.source, command.destination)
            }

            is RetainedDrawCommand.DrawMaskInstances -> {
                val mask = requireBinding(resources, command.mask, RetainedMask1Bpp::class.java)
                val instances =
                    requireBinding(resources, command.instances, RetainedMaskInstanceBuffer::class.java)
                validateInstanceBinding(mask, instances, command.firstInstance, command.instanceCount)
            }

            is RetainedDrawCommand.PushClip,
            RetainedDrawCommand.PopClip,
            is RetainedDrawCommand.FillRect,
            -> {
                Unit
            }
        }
    }
}

private fun readSource(reader: LeReader): RetainedSourceRect =
    RetainedSourceRect(reader.readU16(), reader.readU16(), reader.readU16(), reader.readU16())

private fun readDestination(reader: LeReader): RetainedDestinationRect =
    RetainedDestinationRect(reader.readI16(), reader.readI16(), reader.readU16(), reader.readU16())

private fun validateImageBinding(
    entry: RetainedDisplayResourceEntry,
    source: RetainedSourceRect,
    destination: RetainedDestinationRect,
) {
    val image = entry.content as RetainedImageRgb565
    requireRectangles(source, destination)
    requireSourceBounds(source, image.width, image.height)
}

private fun validateMaskBinding(
    entry: RetainedDisplayResourceEntry,
    source: RetainedSourceRect,
    destination: RetainedDestinationRect,
) {
    val mask = entry.content as RetainedMask1Bpp
    requireRectangles(source, destination)
    requireSourceBounds(source, mask.width, mask.height)
}

private fun validateInstanceBinding(
    maskEntry: RetainedDisplayResourceEntry,
    instanceEntry: RetainedDisplayResourceEntry,
    first: Int,
    count: Int,
) {
    val mask = maskEntry.content as RetainedMask1Bpp
    val buffer = instanceEntry.content as RetainedMaskInstanceBuffer
    if (count == 0 || first + count > buffer.capacity) validationFailure()
    for (instance in buffer.instances.subList(first, first + count)) {
        requireSourceBounds(
            RetainedSourceRect(instance.sourceX, instance.sourceY, instance.sourceWidth, instance.sourceHeight),
            mask.width,
            mask.height,
        )
    }
}

private fun requireRectangles(
    source: RetainedSourceRect,
    destination: RetainedDestinationRect,
) {
    requireExtent(source.width, source.height)
    requireExtent(destination.width, destination.height)
}

private fun requireExtent(
    width: Int,
    height: Int,
) {
    if (width == 0 || height == 0) validationFailure()
}

private fun requireSourceBounds(
    source: RetainedSourceRect,
    width: Int,
    height: Int,
) {
    if (source.x + source.width > width || source.y + source.height > height) validationFailure()
}

private fun requireCommandEnvelope(
    reader: LeReader,
    flags: Int,
    length: Int,
) {
    if (flags != 0) validationFailure()
    requireCommandLength(reader, length)
}

private fun requireCommandLength(
    reader: LeReader,
    length: Int,
) {
    if (reader.size != length) validationFailure()
}

private fun requireResource(
    resources: Map<UInt, RetainedDisplayResourceEntry>,
    resourceId: UInt,
    kind: Class<out RetainedDisplayResource>,
): RetainedDisplayResourceEntry {
    val entry = resources[resourceId] ?: validationFailure()
    if (!kind.isInstance(entry.content)) validationFailure()
    return entry
}

private fun requireBinding(
    resources: Map<UInt, RetainedDisplayResourceEntry>,
    binding: RetainedResourceBinding,
    kind: Class<out RetainedDisplayResource>,
): RetainedDisplayResourceEntry {
    val entry = requireResource(resources, binding.resourceId, kind)
    if (entry.localIdentity != binding.localIdentity) validationFailure()
    return entry
}

private fun RetainedDisplayResourceEntry.binding(): RetainedResourceBinding = RetainedResourceBinding(resourceId, localIdentity)

private fun RetainedDisplayResource.kindOpcode(): Int =
    when (this) {
        is RetainedImageRgb565 -> 1
        is RetainedMask1Bpp -> 2
        is RetainedMaskInstanceBuffer -> 3
    }

private fun RetainedDisplayResource.payloadBytes(): Int =
    when (this) {
        is RetainedImageRgb565 -> width * height * 2
        is RetainedMask1Bpp -> checkedMaskRowBytes(width) * height
        is RetainedMaskInstanceBuffer -> capacity * INSTANCE_BYTES
    }

private fun checkedTotalPayload(
    current: Int,
    resource: RetainedDisplayResource,
): Int {
    val payload = resource.payloadBytes()
    if (payload > MAX_RESOURCE_BYTES || current > MAX_TOTAL_RESOURCE_BYTES - payload) validationFailure()
    return current + payload
}

private fun checkedPatchCount(
    current: Int,
    additional: Int,
): Int {
    if (current > MAX_PATCHES - additional) validationFailure()
    return current + additional
}

private fun checkedArea(
    width: Int,
    height: Int,
): Int {
    if (width == 0 || height == 0) validationFailure()
    val area = width.toLong() * height.toLong()
    if (area > Int.MAX_VALUE) validationFailure()
    return area.toInt()
}

private fun checkedMaskRowBytes(width: Int): Int {
    if (width == 0) validationFailure()
    return (width + 7) / 8
}

private fun validateMaskPadding(
    width: Int,
    height: Int,
    rows: ByteArray,
) {
    val usedBits = width % 8
    if (usedBits == 0) return
    val rowBytes = checkedMaskRowBytes(width)
    val allowed = 0xff shl (8 - usedBits) and 0xff
    repeat(height) { row ->
        if (rows[(row + 1) * rowBytes - 1].toInt() and allowed.inv() and 0xff != 0) validationFailure()
    }
}

private fun allocateIdentity(identity: Long): Long {
    if (identity <= 0) {
        throw ReplicaValidationFailure(RetainedDisplayResyncReason.ATOMIC_INSTALL_FAILED)
    }
    return identity
}

private fun incrementIdentity(identity: Long): Long = if (identity == Long.MAX_VALUE) 0 else identity + 1

private class LeReader private constructor(
    private val bytes: ByteArray,
    private val start: Int,
    private val end: Int,
) {
    constructor(bytes: ByteArray) : this(bytes, 0, bytes.size)

    var position: Int = start
        private set

    val size: Int = end - start
    val remaining: Int
        get() = end - position

    fun readU16(): Int {
        requireRemaining(2)
        val value = (bytes[position].toInt() and 0xff) or ((bytes[position + 1].toInt() and 0xff) shl 8)
        position += 2
        return value
    }

    fun readI16(): Int = readU16().toShort().toInt()

    fun readU32(): UInt {
        requireRemaining(4)
        var value = 0u
        repeat(4) { index -> value = value or ((bytes[position + index].toUInt() and 0xffu) shl (index * 8)) }
        position += 4
        return value
    }

    fun readU64(): ULong {
        requireRemaining(8)
        var value = 0uL
        repeat(8) { index -> value = value or ((bytes[position + index].toULong() and 0xffuL) shl (index * 8)) }
        position += 8
        return value
    }

    fun readCount(maximum: Int): Int {
        val value = readU32().toLong()
        if (value > maximum) validationFailure()
        return value.toInt()
    }

    fun readLength(maximum: Int): Int = readCount(maximum)

    fun readBytes(length: Int): ByteArray {
        requireRemaining(length)
        val result = bytes.copyOfRange(position, position + length)
        position += length
        return result
    }

    fun readSlice(length: Int): LeReader {
        requireRemaining(length)
        return LeReader(bytes, position, position + length).also { position += length }
    }

    fun readRecord(minimumBytes: Int): LeReader {
        if (remaining < 8) validationFailure()
        val length = peekU32(offset = 4).toLong()
        if (length < minimumBytes || length > remaining.toLong()) validationFailure()
        return readSlice(length.toInt())
    }

    fun peekU16(): Int {
        requireRemaining(2)
        return (bytes[position].toInt() and 0xff) or ((bytes[position + 1].toInt() and 0xff) shl 8)
    }

    fun align4AndRequireZeroPadding() {
        val aligned = (position + 3) and 3.inv()
        if (aligned > end) validationFailure()
        while (position < aligned) {
            if (bytes[position].toInt() != 0) validationFailure()
            position += 1
        }
    }

    fun requireEnd() {
        if (position != end) validationFailure()
    }

    private fun peekU32(offset: Int): UInt {
        if (offset < 0 || remaining < offset + 4) validationFailure()
        var value = 0u
        repeat(4) { index -> value = value or ((bytes[position + offset + index].toUInt() and 0xffu) shl (index * 8)) }
        return value
    }

    private fun requireRemaining(length: Int) {
        if (length < 0 || length > remaining) validationFailure()
    }
}
