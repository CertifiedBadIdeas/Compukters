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

import net.minecraft.resources.ResourceLocation
import org.joml.Matrix4f
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedDisplayApplyResult
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedDisplayReplica
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedPatchRectangle
import ru.lazyhat.compukterkraft.core.device.display.retained.render.RetainedGeometryBatch
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class RetainedDisplayRenderingProfilingTest {
    @Test
    fun lateMenuAndWorldViewsShareTexturesAndReleaseFlavorBatchesByReferenceCount() {
        val fixture = cacheFixture()
        val replica = RetainedDisplayReplica()
        fixture.cache.retainView(RetainedDisplayViewKind.MENU, null)
        assertNull(fixture.cache.presentation(RetainedDisplayViewKind.MENU))
        val installed = assertIs<RetainedDisplayApplyResult.Installed>(replica.apply(snapshot()))

        fixture.cache.install(installed.state, installed.damage)
        val menu = fixture.cache.presentation(RetainedDisplayViewKind.MENU)!!
        fixture.cache.retainView(RetainedDisplayViewKind.MENU, installed.state)
        fixture.cache.retainView(RetainedDisplayViewKind.WORLD, installed.state)
        val world = fixture.cache.presentation(RetainedDisplayViewKind.WORLD)!!

        assertNotSame(menu, world)
        assertEquals(1, fixture.metrics.snapshot().textureCreations)
        assertEquals(1, fixture.metrics.snapshot().fullTextureUploads)
        assertEquals(6, fixture.metrics.snapshot().batchCreations)
        assertEquals(2, fixture.metrics.snapshot().instanceChunkCompilations)

        val stable = fixture.metrics.snapshot()
        repeat(100) {
            menu.submit(NOOP_SUBMITTER)
            world.submit(NOOP_SUBMITTER)
        }
        val afterFrames = fixture.metrics.snapshot()
        assertEquals(stable.textureCreations, afterFrames.textureCreations)
        assertEquals(stable.fullTextureUploads, afterFrames.fullTextureUploads)
        assertEquals(stable.subrectangleUploads, afterFrames.subrectangleUploads)
        assertEquals(stable.uploadedPixels, afterFrames.uploadedPixels)
        assertEquals(stable.batchCreations, afterFrames.batchCreations)
        assertEquals(stable.instanceChunkCompilations, afterFrames.instanceChunkCompilations)
        assertEquals(200, afterFrames.frameSubmissions - stable.frameSubmissions)

        fixture.cache.releaseView(RetainedDisplayViewKind.MENU)
        assertSame(menu, fixture.cache.presentation(RetainedDisplayViewKind.MENU))
        assertEquals(0, fixture.metrics.snapshot().batchReleases)
        fixture.cache.releaseView(RetainedDisplayViewKind.MENU)
        assertNull(fixture.cache.presentation(RetainedDisplayViewKind.MENU))
        assertEquals(3, fixture.metrics.snapshot().batchReleases)
        assertEquals(0, fixture.metrics.snapshot().textureReleases)

        fixture.cache.releaseView(RetainedDisplayViewKind.WORLD)
        fixture.cache.close()
        assertEquals(6, fixture.metrics.snapshot().batchReleases)
        assertEquals(1, fixture.metrics.snapshot().textureReleases)
        assertEquals(fixture.metrics.snapshot().batchCreations, fixture.metrics.snapshot().batchReleases)
    }

    @Test
    fun burstAndCircularScrollTouchOneChunkWithoutReuploadingTexturesOrRebuildingVertexBuffers() {
        val fixture = cacheFixture()
        val replica = RetainedDisplayReplica()
        fixture.cache.retainView(RetainedDisplayViewKind.MENU, null)
        val initial = assertIs<RetainedDisplayApplyResult.Installed>(replica.apply(snapshot()))
        fixture.cache.install(initial.state, initial.damage)
        val initialMetrics = fixture.metrics.snapshot()

        val burst = assertIs<RetainedDisplayApplyResult.Installed>(replica.apply(burstDelta()))
        fixture.cache.install(burst.state, burst.damage)
        val afterBurst = fixture.metrics.snapshot()

        assertEquals(initialMetrics.textureCreations, afterBurst.textureCreations)
        assertEquals(initialMetrics.uploadedPixels, afterBurst.uploadedPixels)
        assertEquals(initialMetrics.batchCreations + 1, afterBurst.batchCreations)
        assertEquals(initialMetrics.instanceChunkCompilations + 1, afterBurst.instanceChunkCompilations)

        val beforeScrollTargets = submitted(fixture.cache.presentation(RetainedDisplayViewKind.MENU)!!)
        val scroll = assertIs<RetainedDisplayApplyResult.Installed>(replica.apply(scrollDelta()))
        fixture.cache.install(scroll.state, scroll.damage)
        val afterScrollTargets = submitted(fixture.cache.presentation(RetainedDisplayViewKind.MENU)!!)
        val afterScroll = fixture.metrics.snapshot()

        assertEquals(afterBurst.batchCreations, afterScroll.batchCreations)
        assertEquals(afterBurst.uploadedPixels, afterScroll.uploadedPixels)
        assertEquals(beforeScrollTargets.map { it.target }, afterScrollTargets.map { it.target })
        assertEquals(listOf(0, 10, 10), afterScrollTargets.map { it.translationY })

        fixture.cache.close()
        assertEquals(fixture.metrics.snapshot().batchCreations, fixture.metrics.snapshot().batchReleases)
        assertEquals(fixture.metrics.snapshot().textureCreations, fixture.metrics.snapshot().textureReleases)
    }

    @Test
    fun resourceReloadDropsNativeObjectsButKeepsViewOwnershipForTheNextSnapshot() {
        val fixture = cacheFixture()
        val installed = assertIs<RetainedDisplayApplyResult.Installed>(RetainedDisplayReplica().apply(snapshot()))
        fixture.cache.retainView(RetainedDisplayViewKind.MENU, null)
        fixture.cache.install(installed.state, installed.damage)

        fixture.cache.invalidate()

        assertNull(fixture.cache.presentation(RetainedDisplayViewKind.MENU))
        assertEquals(3, fixture.metrics.snapshot().batchReleases)
        assertEquals(1, fixture.metrics.snapshot().textureReleases)

        fixture.cache.install(installed.state, installed.damage)

        fixture.cache.presentation(RetainedDisplayViewKind.MENU)!!.submit(NOOP_SUBMITTER)
        assertEquals(6, fixture.metrics.snapshot().batchCreations)
        assertEquals(2, fixture.metrics.snapshot().textureCreations)
        fixture.cache.close()
        assertEquals(fixture.metrics.snapshot().batchCreations, fixture.metrics.snapshot().batchReleases)
        assertEquals(fixture.metrics.snapshot().textureCreations, fixture.metrics.snapshot().textureReleases)
    }

    @Test
    fun compositeCloseAttemptsEveryViewAndSharedTextureAfterFailures() {
        val fixture = cacheFixture()
        val installed = assertIs<RetainedDisplayApplyResult.Installed>(RetainedDisplayReplica().apply(snapshot()))
        fixture.cache.retainView(RetainedDisplayViewKind.MENU, null)
        fixture.cache.retainView(RetainedDisplayViewKind.WORLD, null)
        fixture.cache.install(installed.state, installed.damage)
        fixture.batchTargets.values.forEach { it.first().failClose = true }
        fixture.textureTargets.first().failClose = true

        val failure = assertFailsWith<IllegalStateException> { fixture.cache.close() }

        assertEquals(2, failure.suppressed.size)
        assertEquals(listOf(1), fixture.textureTargets.map { it.closes })
        assertEquals(
            listOf(1, 1, 1, 1, 1, 1),
            fixture.batchTargets.values
                .flatten()
                .map { it.closes },
        )
    }

    private fun cacheFixture(): CacheFixture {
        val metrics = RetainedDisplayRenderMetrics()
        val textureTargets = mutableListOf<RecordingTextureTarget>()
        val batchTargets = mutableMapOf<RetainedDisplayViewKind, MutableList<RecordingBatchTarget>>()
        val textureCache =
            MinecraftRetainedTextureCache(
                MinecraftRetainedTextureTargetFactory { identity, width, height, pixels ->
                    RecordingTextureTarget(identity, width, height, pixels).also(textureTargets::add)
                },
                metrics,
            )
        val cache =
            MinecraftRetainedDisplayCache(
                textureCache,
                batchCacheFactory = { viewKind ->
                    val targets = batchTargets.getOrPut(viewKind, ::mutableListOf)
                    MinecraftRetainedBatchCache(
                        MinecraftRetainedBatchTargetFactory { batch ->
                            RecordingBatchTarget(batch).also(targets::add)
                        },
                        metrics,
                    )
                },
            )
        return CacheFixture(cache, metrics, textureTargets, batchTargets)
    }

    private fun submitted(presentation: MinecraftRetainedNativePresentation): List<SubmittedTarget> =
        buildList {
            presentation.submit { target, translationX, translationY ->
                add(SubmittedTarget(target as RecordingBatchTarget, translationX, translationY))
            }
        }

    private data class CacheFixture(
        val cache: MinecraftRetainedDisplayCache,
        val metrics: RetainedDisplayRenderMetrics,
        val textureTargets: List<RecordingTextureTarget>,
        val batchTargets: Map<RetainedDisplayViewKind, List<RecordingBatchTarget>>,
    )

    private data class SubmittedTarget(
        val target: RecordingBatchTarget,
        val translationX: Int,
        val translationY: Int,
    )

    private class RecordingTextureTarget(
        identity: Long,
        override val width: Int,
        override val height: Int,
        initialPixels: IntArray,
    ) : MinecraftRetainedTextureTarget {
        override val location: ResourceLocation =
            ResourceLocation.fromNamespaceAndPath("compukterkraft", "test/profile_$identity")
        private val pixels = initialPixels.copyOf()
        var closes = 0
        var failClose = false

        override fun patch(
            rectangle: RetainedPatchRectangle,
            argb: IntArray,
        ) = Unit

        override fun close() {
            closes += 1
            if (failClose) error("synthetic texture close failure")
        }
    }

    private class RecordingBatchTarget(
        val batch: RetainedGeometryBatch,
    ) : MinecraftRetainedBatchTarget {
        var closes = 0
        var failClose = false

        override fun draw(
            modelView: Matrix4f,
            projection: Matrix4f,
        ) = Unit

        override fun close() {
            closes += 1
            if (failClose) error("synthetic batch close failure")
        }
    }

    private fun snapshot(): ByteArray {
        val mask = fullMask(1u)
        val instances = fullInstances(2u)
        val drawList = drawMaskInstancesList(translationY = 0)
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

    private fun burstDelta(): ByteArray {
        val patch =
            bytes {
                u16(0x0012)
                u16(0)
                u32(128u)
                u32(2u)
                u32(4u)
                listOf(1, 17, 33, 49).forEach { index ->
                    u16(index)
                    u16(1)
                    raw(instance(index, foreground = 0xf800))
                }
            }
        return delta(baseSequence = 1uL, targetSequence = 2uL, changes = listOf(patch))
    }

    private fun scrollDelta(): ByteArray =
        delta(
            baseSequence = 2uL,
            targetSequence = 3uL,
            changes = emptyList(),
            drawList = drawMaskInstancesList(translationY = 10),
        )

    private fun delta(
        baseSequence: ULong,
        targetSequence: ULong,
        changes: List<ByteArray>,
        drawList: ByteArray = ByteArray(0),
    ): ByteArray =
        message(
            kind = 2,
            payload =
                bytes {
                    u64(baseSequence)
                    u64(targetSequence)
                    u32(changes.size.toUInt())
                    u32(drawList.size.toUInt())
                    changes.forEach(::raw)
                    raw(drawList)
                },
        )

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

    private fun fullInstances(resourceId: UInt): ByteArray =
        bytes {
            u16(3)
            u16(0)
            u32((16 + 64 * 24).toUInt())
            u32(resourceId)
            u16(64)
            u16(0)
            repeat(64) { raw(instance(it, foreground = 0xffff)) }
        }

    private fun instance(
        index: Int,
        foreground: Int,
    ): ByteArray =
        bytes {
            u16(0)
            u16(0)
            u16(8)
            u16(1)
            i16(index)
            i16(0)
            u16(1)
            u16(1)
            u16(foreground)
            u16(0x001f)
            u16(1)
            u16(0)
        }

    private fun drawMaskInstancesList(translationY: Int): ByteArray =
        bytes {
            u16(0)
            u16(0)
            u32(1u)
            u16(0x0022)
            u16(0)
            u32(24u)
            u32(1u)
            u32(2u)
            u16(0)
            u16(64)
            i16(0)
            i16(translationY)
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

    private companion object {
        val NOOP_SUBMITTER = MinecraftRetainedBatchSubmitter { _, _, _ -> }
    }
}
