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
import ru.lazyhat.compukters.core.device.display.retained.RetainedDrawList
import ru.lazyhat.compukters.core.device.display.retained.RetainedImageRgb565
import ru.lazyhat.compukters.core.device.display.retained.RetainedMask1Bpp
import ru.lazyhat.compukters.core.device.display.retained.RetainedMaskInstance
import ru.lazyhat.compukters.core.device.display.retained.RetainedMaskInstanceBuffer
import ru.lazyhat.compukters.core.device.display.retained.RetainedPatchRange
import ru.lazyhat.compukters.core.device.display.retained.RetainedResourceBinding
import ru.lazyhat.compukters.core.device.display.retained.RetainedResourceDamage
import ru.lazyhat.compukters.core.device.display.retained.RetainedSourceRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RetainedDisplayGeometryCompilerTest {
    @Test
    fun expandsRgb565WithBitReplication() {
        assertEquals(0xffff0000.toInt(), retainedRgb565ToArgb(0xf800))
        assertEquals(0xff00ff00.toInt(), retainedRgb565ToArgb(0x07e0))
        assertEquals(0xff0000ff.toInt(), retainedRgb565ToArgb(0x001f))
        assertEquals(0xffffffff.toInt(), retainedRgb565ToArgb(0xffff))
    }

    @Test
    fun intersectsNestedClipsBeforeEmittingGeometry() {
        val state =
            state(
                commands =
                    listOf(
                        RetainedDrawCommand.PushClip(2, 2, 4, 4),
                        RetainedDrawCommand.PushClip(4, 0, 4, 8),
                        RetainedDrawCommand.FillRect(0, 0, 10, 10, 0xf800),
                        RetainedDrawCommand.PopClip,
                        RetainedDrawCommand.PopClip,
                    ),
            )

        val presentation = RetainedDisplayGeometryCompiler.compile(state)
        val direct = assertIs<RetainedCompiledCommand.Direct>(presentation.commands.single())
        val quad = direct.batches.single().quads.single()

        assertEquals(RetainedFloatRect(4f, 2f, 2f, 4f), quad.destination)
        assertNull(quad.sourceUv)
        assertEquals(0xffff0000.toInt(), quad.argb)
    }

    @Test
    fun clippingAnImageAdjustsItsNormalizedSourceCoordinates() {
        val image = RetainedImageRgb565(10, 1, ShortArray(10))
        val entry = RetainedDisplayResourceEntry(1u, 11L, image)
        val state =
            state(
                resources = listOf(entry),
                commands =
                    listOf(
                        RetainedDrawCommand.DrawImage(
                            RetainedResourceBinding(1u, 11L),
                            RetainedSourceRect(0, 0, 10, 1),
                            RetainedDestinationRect(-5, 0, 10, 1),
                        ),
                    ),
            )

        val presentation = RetainedDisplayGeometryCompiler.compile(state)
        val quad = assertIs<RetainedCompiledCommand.Direct>(presentation.commands.single()).batches.single().quads.single()

        assertEquals(RetainedFloatRect(0f, 0f, 5f, 1f), quad.destination)
        assertEquals(RetainedFloatRect(0.5f, 0f, 0.5f, 1f), quad.sourceUv)
        assertEquals(11L, quad.textureIdentity)
    }

    @Test
    fun opaqueMaskEmitsBackgroundThenForegroundWithoutBakingColorsIntoTexture() {
        val mask = RetainedDisplayResourceEntry(1u, 12L, RetainedMask1Bpp(8, 1, byteArrayOf(0x80.toByte())))
        val state =
            state(
                resources = listOf(mask),
                commands =
                    listOf(
                        RetainedDrawCommand.DrawMask(
                            RetainedResourceBinding(1u, 12L),
                            RetainedSourceRect(0, 0, 8, 1),
                            RetainedDestinationRect(3, 4, 8, 1),
                            foregroundRgb565 = 0xffff,
                            backgroundRgb565 = 0x001f,
                            opaqueBackground = true,
                        ),
                    ),
            )

        val direct =
            assertIs<RetainedCompiledCommand.Direct>(
                RetainedDisplayGeometryCompiler.compile(state).commands.single(),
            )

        assertEquals(2, direct.batches.size)
        assertNull(direct.batches[0].textureIdentity)
        assertEquals(0xff0000ff.toInt(), direct.batches[0].quads.single().argb)
        assertEquals(12L, direct.batches[1].textureIdentity)
        assertEquals(0xffffffff.toInt(), direct.batches[1].quads.single().argb)
    }

    @Test
    fun alignedInstanceRangesReuseFixedSixtyFourEntryChunks() {
        val state = instanceState(capacity = 128, draws = listOf(instanceDraw(first = 0, count = 128)))

        val presentation = RetainedDisplayGeometryCompiler.compile(state)
        val command = assertIs<RetainedCompiledCommand.InstanceRange>(presentation.commands.single())

        assertEquals(setOf(chunkKey(0), chunkKey(64)), presentation.instanceChunks.keys)
        assertEquals(
            listOf(
                RetainedInstanceSpan.Cached(chunkKey(0), 0, 0),
                RetainedInstanceSpan.Cached(chunkKey(64), 0, 0),
            ),
            command.spans,
        )
        assertEquals(2, presentation.instanceChunks.getValue(chunkKey(0)).batches.size)
    }

    @Test
    fun patchDamageNamesOnlyIntersectingInstanceChunks() {
        val state = instanceState(capacity = 128, draws = listOf(instanceDraw(first = 0, count = 128)))

        val cellDamage =
            RetainedDisplayInstallDamage.Delta(
                listOf(RetainedResourceDamage.InstancesPatched(2u, 22L, listOf(RetainedPatchRange(10, 1)))),
                drawListReplaced = false,
            )
        val rowDamage =
            RetainedDisplayInstallDamage.Delta(
                listOf(RetainedResourceDamage.InstancesPatched(2u, 22L, listOf(RetainedPatchRange(64, 64)))),
                drawListReplaced = false,
            )

        assertEquals(setOf(chunkKey(0)), RetainedDisplayGeometryCompiler.affectedInstanceChunks(state, cellDamage))
        assertEquals(setOf(chunkKey(64)), RetainedDisplayGeometryCompiler.affectedInstanceChunks(state, rowDamage))
    }

    @Test
    fun incrementalInstallRecompilesOnlyTheDamagedChunk() {
        val draws = listOf(instanceDraw(first = 0, count = 128))
        val initialInstances = List(128) { index -> instance(index % 64, index / 64, background = 0x001f) }
        val initial = instanceState(initialInstances, draws)
        val presentation = RetainedDisplayGeometryCompiler.compile(initial)
        val patchedInstances = initialInstances.toMutableList()
        patchedInstances[10] = instance(10, foreground = 0xf800, background = 0x001f)
        val patched = instanceState(patchedInstances, draws)
        val damage =
            RetainedDisplayInstallDamage.Delta(
                listOf(RetainedResourceDamage.InstancesPatched(2u, 22L, listOf(RetainedPatchRange(10, 1)))),
                drawListReplaced = false,
            )

        val updated = RetainedDisplayGeometryCompiler.update(presentation, patched, damage)

        assertNotSame(presentation.instanceChunks.getValue(chunkKey(0)), updated.instanceChunks.getValue(chunkKey(0)))
        assertSame(presentation.instanceChunks.getValue(chunkKey(64)), updated.instanceChunks.getValue(chunkKey(64)))
        assertSame(presentation.commands, updated.commands)
    }

    @Test
    fun incrementalInstallCombinesRepeatedDamageForTheSameInstanceBuffer() {
        val draws = listOf(instanceDraw(first = 0, count = 128))
        val initialInstances = List(128) { index -> instance(index % 64, index / 64, background = 0x001f) }
        val initial = instanceState(initialInstances, draws)
        val presentation = RetainedDisplayGeometryCompiler.compile(initial)
        val patchedInstances = initialInstances.toMutableList()
        patchedInstances[10] = instance(10, foreground = 0xf800, background = 0x001f)
        patchedInstances[70] = instance(6, 1, foreground = 0x07e0, background = 0x001f)
        val patched = instanceState(patchedInstances, draws)
        val damage =
            RetainedDisplayInstallDamage.Delta(
                listOf(
                    RetainedResourceDamage.InstancesPatched(2u, 22L, listOf(RetainedPatchRange(10, 1))),
                    RetainedResourceDamage.InstancesPatched(2u, 22L, listOf(RetainedPatchRange(70, 1))),
                ),
                drawListReplaced = false,
            )

        val updated = RetainedDisplayGeometryCompiler.update(presentation, patched, damage)

        assertNotSame(presentation.instanceChunks.getValue(chunkKey(0)), updated.instanceChunks.getValue(chunkKey(0)))
        assertNotSame(presentation.instanceChunks.getValue(chunkKey(64)), updated.instanceChunks.getValue(chunkKey(64)))
    }

    @Test
    fun partialChunkRangesCompileOnlyBoundaryGeometry() {
        val state = instanceState(capacity = 128, draws = listOf(instanceDraw(first = 1, count = 64)))

        val presentation = RetainedDisplayGeometryCompiler.compile(state)
        val command = assertIs<RetainedCompiledCommand.InstanceRange>(presentation.commands.single())

        assertTrue(command.spans.all { it is RetainedInstanceSpan.Direct })
        assertTrue(presentation.instanceChunks.isEmpty())
        assertEquals(64, command.spans.sumOf { (it as RetainedInstanceSpan.Direct).instanceCount })
    }

    @Test
    fun translatedCircularRowsShareTheSameCachedChunk() {
        val state =
            instanceState(
                capacity = 64,
                draws =
                    listOf(
                        instanceDraw(first = 0, count = 64, translationY = 0),
                        instanceDraw(first = 0, count = 64, translationY = 10),
                    ),
            )

        val presentation = RetainedDisplayGeometryCompiler.compile(state)
        val first = assertIs<RetainedCompiledCommand.InstanceRange>(presentation.commands[0]).spans.single()
        val second = assertIs<RetainedCompiledCommand.InstanceRange>(presentation.commands[1]).spans.single()

        assertEquals(1, presentation.instanceChunks.size)
        assertEquals(RetainedInstanceSpan.Cached(chunkKey(0), 0, 0), first)
        assertEquals(RetainedInstanceSpan.Cached(chunkKey(0), 0, 10), second)
    }

    @Test
    fun overlappingOpaqueInstancesKeepAscendingPairOrder() {
        val instances =
            listOf(
                instance(x = 0, foreground = 0xffff, background = 0x001f),
                instance(x = 0, foreground = 0xf800, background = 0x07e0),
            )
        val state = instanceState(instances, listOf(instanceDraw(first = 0, count = 2)))

        val command =
            assertIs<RetainedCompiledCommand.InstanceRange>(
                RetainedDisplayGeometryCompiler.compile(state).commands.single(),
            )
        val batches = assertIs<RetainedInstanceSpan.Direct>(command.spans.single()).batches

        assertEquals(4, batches.size)
        assertEquals(
            listOf(0xff0000ff.toInt(), 0xffffffff.toInt(), 0xff00ff00.toInt(), 0xffff0000.toInt()),
            batches.map { it.quads.single().argb },
        )
    }

    @Test
    fun compiledSubmissionBudgetAcceptsItsExactMaximumEnvelope() {
        val repeatedChunk = instanceDraw(first = 0, count = 64)
        val draws = List(255) { repeatedChunk } + RetainedDrawCommand.FillRect(0, 0, 1, 1, 0xffff)

        val instances = List(64) { index -> instance(x = index, background = 0x001f) }
        val presentation = RetainedDisplayGeometryCompiler.compile(instanceStateWithCommands(instances, draws))

        assertEquals(RetainedDisplayGeometryCompiler.MAX_BATCH_SUBMISSIONS, presentation.batchSubmissionCount())
    }

    @Test
    fun compiledSubmissionBudgetRejectsRepeatedPathologicalChunks() {
        val overlapping = List(64) { instance(x = 0, background = 0x001f) }
        val draws = List(4) { instanceDraw(first = 0, count = 64) }

        val failure =
            assertFailsWith<RetainedDisplaySubmissionLimitExceeded> {
                RetainedDisplayGeometryCompiler.compile(instanceState(overlapping, draws))
            }

        assertEquals(RetainedDisplayGeometryCompiler.MAX_BATCH_SUBMISSIONS, failure.limit)
        assertEquals(513, failure.attempted)
    }

    private fun state(
        resources: List<RetainedDisplayResourceEntry> = emptyList(),
        commands: List<RetainedDrawCommand>,
    ): RetainedDisplayState =
        RetainedDisplayState(
            computerId = 42u,
            viewerEpoch = 7uL,
            sequence = 1uL,
            resources = resources,
            drawList = RetainedDrawList(0, commands),
        )

    private fun instanceState(
        capacity: Int,
        draws: List<RetainedDrawCommand.DrawMaskInstances>,
    ): RetainedDisplayState =
        instanceState(
            List(capacity) { index -> instance(x = index % 64, y = index / 64, background = 0x001f) },
            draws,
        )

    private fun instanceState(
        instances: List<RetainedMaskInstance>,
        draws: List<RetainedDrawCommand.DrawMaskInstances>,
    ): RetainedDisplayState =
        state(
            resources =
                listOf(
                    RetainedDisplayResourceEntry(1u, 11L, RetainedMask1Bpp(8, 1, byteArrayOf(0x80.toByte()))),
                    RetainedDisplayResourceEntry(2u, 22L, RetainedMaskInstanceBuffer(instances.size, instances)),
                ),
            commands = draws,
        )

    private fun instanceStateWithCommands(
        instances: List<RetainedMaskInstance>,
        commands: List<RetainedDrawCommand>,
    ): RetainedDisplayState =
        state(
            resources =
                listOf(
                    RetainedDisplayResourceEntry(1u, 11L, RetainedMask1Bpp(8, 1, byteArrayOf(0x80.toByte()))),
                    RetainedDisplayResourceEntry(2u, 22L, RetainedMaskInstanceBuffer(instances.size, instances)),
                ),
            commands = commands,
        )

    private fun instanceDraw(
        first: Int,
        count: Int,
        translationY: Int = 0,
    ): RetainedDrawCommand.DrawMaskInstances =
        RetainedDrawCommand.DrawMaskInstances(
            mask = RetainedResourceBinding(1u, 11L),
            instances = RetainedResourceBinding(2u, 22L),
            firstInstance = first,
            instanceCount = count,
            translationX = 0,
            translationY = translationY,
        )

    private fun instance(
        x: Int,
        y: Int = 0,
        foreground: Int = 0xffff,
        background: Int = 0,
    ): RetainedMaskInstance =
        RetainedMaskInstance(
            sourceX = 0,
            sourceY = 0,
            sourceWidth = 8,
            sourceHeight = 1,
            destinationX = x,
            destinationY = y,
            destinationWidth = 1,
            destinationHeight = 1,
            foregroundRgb565 = foreground,
            backgroundRgb565 = background,
            opaqueBackground = background != 0,
        )

    private fun chunkKey(first: Int): RetainedInstanceChunkKey = RetainedInstanceChunkKey(11L, 22L, first)
}
