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

package ru.lazyhat.compukters.core.device.display.retained.render

import ru.lazyhat.compukters.core.device.display.retained.RetainedDestinationRect
import ru.lazyhat.compukters.core.device.display.retained.RetainedDisplayInstallDamage
import ru.lazyhat.compukters.core.device.display.retained.RetainedDisplayResourceEntry
import ru.lazyhat.compukters.core.device.display.retained.RetainedDisplayState
import ru.lazyhat.compukters.core.device.display.retained.RetainedDrawCommand
import ru.lazyhat.compukters.core.device.display.retained.RetainedImageRgb565
import ru.lazyhat.compukters.core.device.display.retained.RetainedMask1Bpp
import ru.lazyhat.compukters.core.device.display.retained.RetainedMaskInstance
import ru.lazyhat.compukters.core.device.display.retained.RetainedMaskInstanceBuffer
import ru.lazyhat.compukters.core.device.display.retained.RetainedPatchRange
import ru.lazyhat.compukters.core.device.display.retained.RetainedResourceBinding
import ru.lazyhat.compukters.core.device.display.retained.RetainedResourceDamage
import ru.lazyhat.compukters.core.device.display.retained.RetainedSourceRect
import java.util.ArrayDeque

object RetainedDisplayGeometryCompiler {
    const val LOGICAL_WIDTH: Int = 320
    const val LOGICAL_HEIGHT: Int = 200
    const val INSTANCE_CHUNK_SIZE: Int = 64
    const val MAX_BATCH_SUBMISSIONS: Int = 512

    fun compile(state: RetainedDisplayState): RetainedCompiledPresentation {
        val resources = state.resources.associateBy { it.localIdentity }
        val initialClip = RetainedIntegerRect(0, 0, LOGICAL_WIDTH, LOGICAL_HEIGHT)
        val clips = ArrayDeque<RetainedIntegerRect>().apply { addLast(initialClip) }
        val commands = mutableListOf<RetainedCompiledCommand>()
        val chunks = linkedMapOf<RetainedInstanceChunkKey, RetainedInstanceChunk>()
        var batchSubmissions = 1

        fun append(command: RetainedCompiledCommand) {
            val attempted = batchSubmissions + command.batchSubmissionCount(chunks)
            if (attempted > MAX_BATCH_SUBMISSIONS) {
                throw RetainedDisplaySubmissionLimitExceeded(attempted, MAX_BATCH_SUBMISSIONS)
            }
            batchSubmissions = attempted
            commands += command
        }

        for (command in state.drawList.commands) {
            when (command) {
                is RetainedDrawCommand.PushClip -> {
                    val requested = RetainedIntegerRect.from(command.x, command.y, command.width, command.height)
                    clips.addLast(clips.last().intersection(requested) ?: RetainedIntegerRect.EMPTY)
                }

                RetainedDrawCommand.PopClip -> {
                    clips.removeLast()
                }

                is RetainedDrawCommand.FillRect -> {
                    solidQuad(
                        RetainedIntegerRect.from(command.x, command.y, command.width, command.height),
                        clips.last(),
                        command.rgb565,
                    )?.let { append(RetainedCompiledCommand.Direct(listOf(batch(null, listOf(it))))) }
                }

                is RetainedDrawCommand.DrawImage -> {
                    val image = resources.requireBound(command.image, RetainedImageRgb565::class.java)
                    texturedQuad(
                        command.source,
                        command.destination,
                        clips.last(),
                        image.localIdentity,
                        retainedRgb565ToArgb(0xffff),
                        (image.content as RetainedImageRgb565).width,
                        image.content.height,
                    )?.let { append(RetainedCompiledCommand.Direct(listOf(batch(image.localIdentity, listOf(it))))) }
                }

                is RetainedDrawCommand.DrawMask -> {
                    val mask = resources.requireBound(command.mask, RetainedMask1Bpp::class.java)
                    val batches =
                        compileMask(
                            command.source,
                            command.destination,
                            clips.last(),
                            mask,
                            command.foregroundRgb565,
                            command.backgroundRgb565,
                            command.opaqueBackground,
                        )
                    if (batches.isNotEmpty()) append(RetainedCompiledCommand.Direct(batches))
                }

                is RetainedDrawCommand.DrawMaskInstances -> {
                    val mask = resources.requireBound(command.mask, RetainedMask1Bpp::class.java)
                    val instances = resources.requireBound(command.instances, RetainedMaskInstanceBuffer::class.java)
                    append(compileInstanceRange(command, mask, instances, clips.last(), chunks))
                }
            }
        }

        val backgroundQuad =
            RetainedQuad(
                RetainedFloatRect(0f, 0f, LOGICAL_WIDTH.toFloat(), LOGICAL_HEIGHT.toFloat()),
                sourceUv = null,
                argb = retainedRgb565ToArgb(state.drawList.backgroundRgb565),
                textureIdentity = null,
            )
        return RetainedCompiledPresentation(
            background = batch(null, listOf(backgroundQuad)),
            commands = commands,
            instanceChunks = chunks,
        )
    }

    fun update(
        previous: RetainedCompiledPresentation,
        state: RetainedDisplayState,
        damage: RetainedDisplayInstallDamage,
    ): RetainedCompiledPresentation {
        if (damage !is RetainedDisplayInstallDamage.Delta || damage.drawListReplaced) return compile(state)
        val instanceDamage = damage.resourceChanges.filterIsInstance<RetainedResourceDamage.InstancesPatched>()
        if (instanceDamage.isEmpty()) return previous
        val rangesByIdentity = instanceDamage.groupBy({ it.localIdentity }, { it.ranges }).mapValues { it.value.flatten() }
        val affectedChunks = affectedInstanceChunks(state, damage)
        val chunks = LinkedHashMap(previous.instanceChunks)
        for (key in affectedChunks) {
            if (key in chunks) chunks[key] = compileChunk(state, key)
        }

        var commandsChanged = false
        val commands =
            previous.commands.map commandMap@{ command ->
                if (command !is RetainedCompiledCommand.InstanceRange) return@commandMap command
                var spansChanged = false
                val spans =
                    command.spans.map spanMap@{ span ->
                        if (span !is RetainedInstanceSpan.Direct) return@spanMap span
                        val patchedRanges = rangesByIdentity[span.instanceBufferIdentity] ?: return@spanMap span
                        if (patchedRanges.none { it.intersects(span.firstInstance, span.instanceCount) }) {
                            return@spanMap span
                        }
                        spansChanged = true
                        recompileDirectSpan(state, span)
                    }
                if (spansChanged) {
                    commandsChanged = true
                    RetainedCompiledCommand.InstanceRange(spans)
                } else {
                    command
                }
            }
        val updated =
            RetainedCompiledPresentation(
                previous.background,
                if (commandsChanged) commands else previous.commands,
                chunks,
            )
        val submissions = updated.batchSubmissionCount()
        if (submissions > MAX_BATCH_SUBMISSIONS) {
            throw RetainedDisplaySubmissionLimitExceeded(submissions, MAX_BATCH_SUBMISSIONS)
        }
        return updated
    }

    fun affectedInstanceChunks(
        state: RetainedDisplayState,
        damage: RetainedDisplayInstallDamage,
    ): Set<RetainedInstanceChunkKey> {
        if (damage !is RetainedDisplayInstallDamage.Delta) return emptySet()
        val patched = damage.resourceChanges.filterIsInstance<RetainedResourceDamage.InstancesPatched>()
        if (patched.isEmpty()) return emptySet()
        val rangesByIdentity = patched.groupBy({ it.localIdentity }, { it.ranges }).mapValues { it.value.flatten() }
        val affected = linkedSetOf<RetainedInstanceChunkKey>()
        for (command in state.drawList.commands.filterIsInstance<RetainedDrawCommand.DrawMaskInstances>()) {
            val ranges = rangesByIdentity[command.instances.localIdentity] ?: continue
            for (range in ranges) {
                val first = maxOf(range.first, command.firstInstance)
                val end = minOf(range.first + range.count, command.firstInstance + command.instanceCount)
                var chunkFirst = first / INSTANCE_CHUNK_SIZE * INSTANCE_CHUNK_SIZE
                while (chunkFirst < end) {
                    affected +=
                        RetainedInstanceChunkKey(
                            command.mask.localIdentity,
                            command.instances.localIdentity,
                            chunkFirst,
                        )
                    chunkFirst += INSTANCE_CHUNK_SIZE
                }
            }
        }
        return affected
    }

    private fun compileInstanceRange(
        command: RetainedDrawCommand.DrawMaskInstances,
        maskEntry: RetainedDisplayResourceEntry,
        instancesEntry: RetainedDisplayResourceEntry,
        clip: RetainedIntegerRect,
        chunks: MutableMap<RetainedInstanceChunkKey, RetainedInstanceChunk>,
    ): RetainedCompiledCommand.InstanceRange {
        val mask = maskEntry.content as RetainedMask1Bpp
        val instances = instancesEntry.content as RetainedMaskInstanceBuffer
        val spans = mutableListOf<RetainedInstanceSpan>()
        val rangeEnd = command.firstInstance + command.instanceCount
        var cursor = command.firstInstance
        while (cursor < rangeEnd) {
            val chunkFirst = cursor / INSTANCE_CHUNK_SIZE * INSTANCE_CHUNK_SIZE
            val chunkEnd = minOf(chunkFirst + INSTANCE_CHUNK_SIZE, instances.capacity)
            val spanEnd = minOf(chunkEnd, rangeEnd)
            val spanInstances = instances.instances.subList(cursor, spanEnd)
            val isFullFixedChunk =
                cursor == chunkFirst &&
                    spanEnd - cursor == INSTANCE_CHUNK_SIZE &&
                    spanInstances.all {
                        clip.contains(
                            RetainedIntegerRect.from(
                                it.destinationX + command.translationX,
                                it.destinationY + command.translationY,
                                it.destinationWidth,
                                it.destinationHeight,
                            ),
                        )
                    }
            if (isFullFixedChunk) {
                val key = RetainedInstanceChunkKey(maskEntry.localIdentity, instancesEntry.localIdentity, chunkFirst)
                chunks.getOrPut(key) {
                    RetainedInstanceChunk(
                        key,
                        compileInstances(
                            instances.instances.subList(chunkFirst, chunkFirst + INSTANCE_CHUNK_SIZE),
                            maskEntry.localIdentity,
                            mask.width,
                            mask.height,
                            translationX = 0,
                            translationY = 0,
                            clip = null,
                        ),
                    )
                }
                spans += RetainedInstanceSpan.Cached(key, command.translationX, command.translationY)
            } else {
                spans +=
                    RetainedInstanceSpan.Direct(
                        firstInstance = cursor,
                        instanceCount = spanEnd - cursor,
                        maskIdentity = maskEntry.localIdentity,
                        instanceBufferIdentity = instancesEntry.localIdentity,
                        translationX = command.translationX,
                        translationY = command.translationY,
                        clip = clip,
                        batches =
                            compileInstances(
                                spanInstances,
                                maskEntry.localIdentity,
                                mask.width,
                                mask.height,
                                command.translationX,
                                command.translationY,
                                clip,
                            ),
                    )
            }
            cursor = spanEnd
        }
        return RetainedCompiledCommand.InstanceRange(spans)
    }

    private fun compileChunk(
        state: RetainedDisplayState,
        key: RetainedInstanceChunkKey,
    ): RetainedInstanceChunk {
        val resources = state.resources.associateBy { it.localIdentity }
        val maskEntry =
            resources[key.maskIdentity]
                ?.takeIf { it.content is RetainedMask1Bpp }
                ?: error("Retained instance chunk mask is not installed: ${key.maskIdentity}")
        val instancesEntry =
            resources[key.instanceBufferIdentity]
                ?.takeIf { it.content is RetainedMaskInstanceBuffer }
                ?: error("Retained instance chunk buffer is not installed: ${key.instanceBufferIdentity}")
        val mask = maskEntry.content as RetainedMask1Bpp
        val instances = instancesEntry.content as RetainedMaskInstanceBuffer
        check(key.firstInstance >= 0 && key.firstInstance + INSTANCE_CHUNK_SIZE <= instances.capacity)
        return RetainedInstanceChunk(
            key,
            compileInstances(
                instances.instances.subList(key.firstInstance, key.firstInstance + INSTANCE_CHUNK_SIZE),
                key.maskIdentity,
                mask.width,
                mask.height,
                translationX = 0,
                translationY = 0,
                clip = null,
            ),
        )
    }

    private fun recompileDirectSpan(
        state: RetainedDisplayState,
        span: RetainedInstanceSpan.Direct,
    ): RetainedInstanceSpan.Direct {
        val resources = state.resources.associateBy { it.localIdentity }
        val mask =
            resources[span.maskIdentity]?.content as? RetainedMask1Bpp
                ?: error("Retained direct span mask is not installed: ${span.maskIdentity}")
        val instances =
            resources[span.instanceBufferIdentity]?.content as? RetainedMaskInstanceBuffer
                ?: error("Retained direct span buffer is not installed: ${span.instanceBufferIdentity}")
        return span.copy(
            batches =
                compileInstances(
                    instances.instances.subList(span.firstInstance, span.firstInstance + span.instanceCount),
                    span.maskIdentity,
                    mask.width,
                    mask.height,
                    span.translationX,
                    span.translationY,
                    span.clip,
                ),
        )
    }

    private fun compileInstances(
        instances: List<RetainedMaskInstance>,
        maskIdentity: Long,
        maskWidth: Int,
        maskHeight: Int,
        translationX: Int,
        translationY: Int,
        clip: RetainedIntegerRect?,
    ): List<RetainedGeometryBatch> {
        val compiled =
            instances.mapNotNull { instance ->
                val destination =
                    RetainedDestinationRect(
                        instance.destinationX + translationX,
                        instance.destinationY + translationY,
                        instance.destinationWidth,
                        instance.destinationHeight,
                    )
                val visible =
                    RetainedIntegerRect
                        .from(destination.x, destination.y, destination.width, destination.height)
                        .let { if (clip == null) it else it.intersection(clip) }
                        ?: return@mapNotNull null
                val background =
                    if (instance.opaqueBackground) {
                        RetainedQuad(
                            visible.toFloatRect(),
                            sourceUv = null,
                            argb = retainedRgb565ToArgb(instance.backgroundRgb565),
                            textureIdentity = null,
                        )
                    } else {
                        null
                    }
                val foreground =
                    texturedQuad(
                        RetainedSourceRect(
                            instance.sourceX,
                            instance.sourceY,
                            instance.sourceWidth,
                            instance.sourceHeight,
                        ),
                        destination,
                        clip ?: visible,
                        maskIdentity,
                        retainedRgb565ToArgb(instance.foregroundRgb565),
                        maskWidth,
                        maskHeight,
                    ) ?: return@mapNotNull null
                CompiledInstance(visible, background, foreground)
            }
        if (compiled.isEmpty()) return emptyList()

        val result = mutableListOf<RetainedGeometryBatch>()
        val run = mutableListOf<CompiledInstance>()

        fun flushRun() {
            if (run.isEmpty()) return
            val backgrounds = run.mapNotNull { it.background }
            if (backgrounds.isNotEmpty()) result += batch(null, backgrounds)
            result += batch(maskIdentity, run.map { it.foreground })
            run.clear()
        }
        for (instance in compiled) {
            if (run.any { it.destination.overlaps(instance.destination) }) flushRun()
            run += instance
        }
        flushRun()
        return result
    }

    private fun compileMask(
        source: RetainedSourceRect,
        destination: RetainedDestinationRect,
        clip: RetainedIntegerRect,
        maskEntry: RetainedDisplayResourceEntry,
        foregroundRgb565: Int,
        backgroundRgb565: Int,
        opaqueBackground: Boolean,
    ): List<RetainedGeometryBatch> {
        val mask = maskEntry.content as RetainedMask1Bpp
        val foreground =
            texturedQuad(
                source,
                destination,
                clip,
                maskEntry.localIdentity,
                retainedRgb565ToArgb(foregroundRgb565),
                mask.width,
                mask.height,
            ) ?: return emptyList()
        if (!opaqueBackground) return listOf(batch(maskEntry.localIdentity, listOf(foreground)))
        val visible =
            RetainedIntegerRect
                .from(destination.x, destination.y, destination.width, destination.height)
                .intersection(clip)
                ?: return emptyList()
        val background =
            RetainedQuad(
                visible.toFloatRect(),
                sourceUv = null,
                argb = retainedRgb565ToArgb(backgroundRgb565),
                textureIdentity = null,
            )
        return listOf(batch(null, listOf(background)), batch(maskEntry.localIdentity, listOf(foreground)))
    }

    private fun solidQuad(
        destination: RetainedIntegerRect,
        clip: RetainedIntegerRect,
        rgb565: Int,
    ): RetainedQuad? {
        val visible = destination.intersection(clip) ?: return null
        return RetainedQuad(
            visible.toFloatRect(),
            sourceUv = null,
            argb = retainedRgb565ToArgb(rgb565),
            textureIdentity = null,
        )
    }

    private fun texturedQuad(
        source: RetainedSourceRect,
        destination: RetainedDestinationRect,
        clip: RetainedIntegerRect,
        textureIdentity: Long,
        argb: Int,
        textureWidth: Int,
        textureHeight: Int,
    ): RetainedQuad? {
        val destinationRect = RetainedIntegerRect.from(destination.x, destination.y, destination.width, destination.height)
        val visible = destinationRect.intersection(clip) ?: return null
        val sourceX = source.x + (visible.left - destinationRect.left).toFloat() * source.width / destination.width
        val sourceY = source.y + (visible.top - destinationRect.top).toFloat() * source.height / destination.height
        val sourceWidth = visible.width.toFloat() * source.width / destination.width
        val sourceHeight = visible.height.toFloat() * source.height / destination.height
        return RetainedQuad(
            visible.toFloatRect(),
            RetainedFloatRect(
                sourceX / textureWidth,
                sourceY / textureHeight,
                sourceWidth / textureWidth,
                sourceHeight / textureHeight,
            ),
            argb,
            textureIdentity,
        )
    }

    private fun batch(
        textureIdentity: Long?,
        quads: List<RetainedQuad>,
    ): RetainedGeometryBatch = RetainedGeometryBatch(textureIdentity, quads)

    private fun <T> Map<Long, RetainedDisplayResourceEntry>.requireBound(
        binding: RetainedResourceBinding,
        contentType: Class<T>,
    ): RetainedDisplayResourceEntry {
        val entry = get(binding.localIdentity)
        require(entry != null && entry.resourceId == binding.resourceId && contentType.isInstance(entry.content)) {
            "Retained draw binding does not resolve to installed resource $binding"
        }
        return entry
    }

    private data class CompiledInstance(
        val destination: RetainedIntegerRect,
        val background: RetainedQuad?,
        val foreground: RetainedQuad,
    )

    private fun RetainedPatchRange.intersects(
        firstInstance: Int,
        instanceCount: Int,
    ): Boolean = first < firstInstance + instanceCount && first + count > firstInstance
}
