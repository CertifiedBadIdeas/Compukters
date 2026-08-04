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

package ru.lazyhat.compukterkraft.common.computer.client.retained

import org.joml.Matrix4f
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedDisplayApplyResult
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedDisplayReplica
import ru.lazyhat.compukterkraft.core.device.display.retained.render.RetainedGeometryBatch
import ru.lazyhat.compukterkraft.core.gui.TerminalRect
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class RetainedDisplayMenuRendererTest {
    @Test
    fun unchangedFramesSubmitTheSameCachedTargetsInExactDrawOrder() {
        val targets = mutableListOf<RecordingBatchTarget>()
        val metrics = RetainedDisplayRenderMetrics()
        val cache = MinecraftRetainedBatchCache(recordingFactory(targets), metrics)
        val installed = assertIs<RetainedDisplayApplyResult.Installed>(RetainedDisplayReplica().apply(snapshot(64)))

        cache.install(installed.state, installed.damage)
        val presentation = cache.presentation()
        val firstFrame = mutableListOf<SubmittedTarget>()
        val secondFrame = mutableListOf<SubmittedTarget>()
        presentation.submit { target, translationX, translationY ->
            firstFrame += SubmittedTarget(target as RecordingBatchTarget, translationX, translationY)
        }
        presentation.submit { target, translationX, translationY ->
            secondFrame += SubmittedTarget(target as RecordingBatchTarget, translationX, translationY)
        }

        assertEquals(3, targets.size)
        assertEquals(firstFrame, secondFrame)
        assertEquals(listOf(0L, 0L, 1L), firstFrame.map { it.target.batch.textureIdentity ?: 0L })
        assertEquals(listOf(0 to 0, 3 to 4, 3 to 4), firstFrame.map { it.translationX to it.translationY })
        assertEquals(3, metrics.snapshot().batchCreations)
        assertEquals(1, metrics.snapshot().instanceChunkCompilations)
        assertEquals(2, metrics.snapshot().frameSubmissions)
        assertEquals(0, metrics.snapshot().uploadedPixels)
    }

    @Test
    fun oneCellPatchReplacesOnlyTheChangedForegroundBatch() {
        val targets = mutableListOf<RecordingBatchTarget>()
        val metrics = RetainedDisplayRenderMetrics()
        val cache = MinecraftRetainedBatchCache(recordingFactory(targets), metrics)
        val replica = RetainedDisplayReplica()
        val initial = assertIs<RetainedDisplayApplyResult.Installed>(replica.apply(snapshot(64)))
        cache.install(initial.state, initial.damage)
        val before = submitted(cache)

        val patched = assertIs<RetainedDisplayApplyResult.Installed>(replica.apply(instancePatch(2uL, 10, 1)))
        cache.install(patched.state, patched.damage)
        val after = submitted(cache)

        assertSame(before[0].target, after[0].target)
        assertSame(before[1].target, after[1].target)
        assertNotSame(before[2].target, after[2].target)
        assertEquals(1, before[2].target.closes)
        assertEquals(4, metrics.snapshot().batchCreations)
        assertEquals(1, metrics.snapshot().batchReleases)
        assertEquals(2, metrics.snapshot().instanceChunkCompilations)
    }

    @Test
    fun alignedRowPatchRebuildsOnlyItsSixtyFourInstanceChunk() {
        val targets = mutableListOf<RecordingBatchTarget>()
        val metrics = RetainedDisplayRenderMetrics()
        val cache = MinecraftRetainedBatchCache(recordingFactory(targets), metrics)
        val replica = RetainedDisplayReplica()
        val initial = assertIs<RetainedDisplayApplyResult.Installed>(replica.apply(snapshot(128)))
        cache.install(initial.state, initial.damage)
        val before = submitted(cache)

        val patched = assertIs<RetainedDisplayApplyResult.Installed>(replica.apply(instancePatch(2uL, 64, 64)))
        cache.install(patched.state, patched.damage)
        val after = submitted(cache)

        assertEquals(5, before.size)
        assertSame(before[0].target, after[0].target)
        assertSame(before[1].target, after[1].target)
        assertSame(before[2].target, after[2].target)
        assertSame(before[3].target, after[3].target)
        assertNotSame(before[4].target, after[4].target)
        assertEquals(3, metrics.snapshot().instanceChunkCompilations)
    }

    @Test
    fun viewportUsesNearestIntegerScaleWhenItFitsAndCentersFractionalDownscale() {
        assertEquals(
            RetainedDisplayViewport(30f, 50f, 640f, 400f, 2f),
            RetainedDisplayMenuRenderer.viewport(TerminalRect(0, 0, 700, 500)),
        )
        assertEquals(
            RetainedDisplayViewport(120f, 0f, 160f, 100f, 0.5f),
            RetainedDisplayMenuRenderer.viewport(TerminalRect(0, 0, 400, 100)),
        )
    }

    @Test
    fun closeReleasesEveryCurrentBatchExactlyOnce() {
        val targets = mutableListOf<RecordingBatchTarget>()
        val metrics = RetainedDisplayRenderMetrics()
        val cache = MinecraftRetainedBatchCache(recordingFactory(targets), metrics)
        val installed = assertIs<RetainedDisplayApplyResult.Installed>(RetainedDisplayReplica().apply(snapshot(64)))
        cache.install(installed.state, installed.damage)

        cache.close()
        cache.close()

        assertEquals(listOf(1, 1, 1), targets.map { it.closes })
        assertEquals(3, metrics.snapshot().batchReleases)
    }

    private fun submitted(cache: MinecraftRetainedBatchCache): List<SubmittedTarget> =
        buildList {
            cache.presentation().submit { target, translationX, translationY ->
                add(SubmittedTarget(target as RecordingBatchTarget, translationX, translationY))
            }
        }

    private fun recordingFactory(targets: MutableList<RecordingBatchTarget>) =
        MinecraftRetainedBatchTargetFactory { batch -> RecordingBatchTarget(batch).also(targets::add) }

    private data class SubmittedTarget(
        val target: RecordingBatchTarget,
        val translationX: Int,
        val translationY: Int,
    )

    private class RecordingBatchTarget(
        val batch: RetainedGeometryBatch,
    ) : MinecraftRetainedBatchTarget {
        var closes = 0

        override fun draw(
            modelView: Matrix4f,
            projection: Matrix4f,
        ) = Unit

        override fun close() {
            closes += 1
        }
    }

    private fun snapshot(capacity: Int): ByteArray {
        val mask = fullMask(1u)
        val instances = fullInstances(2u, capacity)
        val drawList = drawMaskInstancesList(1u, 2u, capacity)
        return message(
            kind = 1,
            payload =
                bytes {
                    u64(1uL)
                    u32(2u)
                    u32(drawList.size.toUInt())
                    raw(mask)
                    raw(instances)
                    raw(drawList)
                },
        )
    }

    private fun instancePatch(
        targetSequence: ULong,
        first: Int,
        count: Int,
    ): ByteArray {
        val patch =
            bytes {
                u16(0x0012)
                u16(0)
                u32((20 + count * 24).toUInt())
                u32(2u)
                u32(1u)
                u16(first)
                u16(count)
                repeat(count) { raw(instance((first + it) % 64, (first + it) / 64, foreground = 0xf800)) }
            }
        return message(
            kind = 2,
            payload =
                bytes {
                    u64(targetSequence - 1uL)
                    u64(targetSequence)
                    u32(1u)
                    u32(0u)
                    raw(patch)
                },
        )
    }

    private fun fullMask(resourceId: UInt): ByteArray =
        bytes {
            u16(2)
            u16(0)
            u32(17u)
            u32(resourceId)
            u16(8)
            u16(1)
            raw(byteArrayOf(0x80.toByte()))
        }

    private fun fullInstances(
        resourceId: UInt,
        capacity: Int,
    ): ByteArray =
        bytes {
            u16(3)
            u16(0)
            u32((16 + capacity * 24).toUInt())
            u32(resourceId)
            u16(capacity)
            u16(0)
            repeat(capacity) { raw(instance(it % 64, it / 64, foreground = 0xffff)) }
        }

    private fun instance(
        x: Int,
        y: Int,
        foreground: Int,
    ): ByteArray =
        bytes {
            u16(0)
            u16(0)
            u16(8)
            u16(1)
            i16(x)
            i16(y)
            u16(1)
            u16(1)
            u16(foreground)
            u16(0x001f)
            u16(1)
            u16(0)
        }

    private fun drawMaskInstancesList(
        maskId: UInt,
        instancesId: UInt,
        count: Int,
    ): ByteArray =
        bytes {
            u16(0)
            u16(0)
            u32(1u)
            u16(0x0022)
            u16(0)
            u32(24u)
            u32(maskId)
            u32(instancesId)
            u16(0)
            u16(count)
            i16(3)
            i16(4)
        }

    private fun message(
        kind: Int,
        payload: ByteArray,
    ): ByteArray =
        bytes {
            u32(0x5053_444bu)
            u16(1)
            u16(kind)
            u32((24 + payload.size).toUInt())
            u32(42u)
            u64(7uL)
            raw(payload)
        }

    private fun bytes(block: LeBytes.() -> Unit): ByteArray = LeBytes().apply(block).toByteArray()

    private class LeBytes {
        private val output = ByteArrayOutputStream()

        fun raw(bytes: ByteArray) = output.write(bytes)

        fun i16(value: Int) = u16(value and 0xffff)

        fun u16(value: Int) {
            output.write(value and 0xff)
            output.write(value ushr 8 and 0xff)
        }

        fun u32(value: UInt) {
            repeat(4) { output.write((value shr (it * 8)).toInt() and 0xff) }
        }

        fun u64(value: ULong) {
            repeat(8) { output.write((value shr (it * 8)).toInt() and 0xff) }
        }

        fun toByteArray(): ByteArray = output.toByteArray()
    }
}
