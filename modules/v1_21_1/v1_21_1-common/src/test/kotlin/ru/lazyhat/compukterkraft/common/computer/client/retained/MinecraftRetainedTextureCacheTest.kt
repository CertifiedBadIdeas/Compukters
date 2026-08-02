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
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedDisplayApplyResult
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedDisplayReplica
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedPatchRectangle
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class MinecraftRetainedTextureCacheTest {
    @Test
    fun fullReplacementCreatesExactOpaqueImageAndColorlessMaskTextures() {
        val targets = mutableListOf<RecordingTextureTarget>()
        val metrics = RetainedDisplayRenderMetrics()
        val cache = MinecraftRetainedTextureCache(recordingFactory(targets), metrics)
        val installed = assertIs<RetainedDisplayApplyResult.Installed>(RetainedDisplayReplica().apply(snapshot()))

        cache.install(installed.state, installed.damage)

        assertEquals(2, targets.size)
        assertEquals(listOf(0xffff0000.toInt(), 0xff00ff00.toInt()), targets[0].pixels.toList())
        assertEquals(
            listOf(0xffffffff.toInt()) + List(7) { 0x00ffffff },
            targets[1].pixels.toList(),
        )
        assertEquals(2, metrics.snapshot().textureCreations)
        assertEquals(2, metrics.snapshot().fullTextureUploads)
        assertEquals(10, metrics.snapshot().uploadedPixels)
    }

    @Test
    fun typedPatchesUpdateOnlyBoundedTextureRectangles() {
        val targets = mutableListOf<RecordingTextureTarget>()
        val metrics = RetainedDisplayRenderMetrics()
        val cache = MinecraftRetainedTextureCache(recordingFactory(targets), metrics)
        val replica = RetainedDisplayReplica()
        val snapshot = assertIs<RetainedDisplayApplyResult.Installed>(replica.apply(snapshot()))
        cache.install(snapshot.state, snapshot.damage)
        val imageTarget = targets[0]
        val maskTarget = targets[1]

        val delta = assertIs<RetainedDisplayApplyResult.Installed>(replica.apply(patchDelta()))
        cache.install(delta.state, delta.damage)

        assertSame(imageTarget, targets[0])
        assertSame(maskTarget, targets[1])
        assertEquals(listOf(RetainedPatchRectangle(1, 0, 1, 1)), imageTarget.patches)
        assertEquals(listOf(RetainedPatchRectangle(1, 0, 1, 1)), maskTarget.patches)
        assertEquals(0xff0000ff.toInt(), imageTarget.pixels[1])
        assertEquals(0xffffffff.toInt(), maskTarget.pixels[1])
        assertEquals(2, metrics.snapshot().subrectangleUploads)
        assertEquals(12, metrics.snapshot().uploadedPixels)
    }

    @Test
    fun resourceRecreationReleasesOldTextureAndCreatesNewLocalIdentity() {
        val targets = mutableListOf<RecordingTextureTarget>()
        val metrics = RetainedDisplayRenderMetrics()
        val cache = MinecraftRetainedTextureCache(recordingFactory(targets), metrics)
        val replica = RetainedDisplayReplica()
        val snapshot = assertIs<RetainedDisplayApplyResult.Installed>(replica.apply(snapshot()))
        cache.install(snapshot.state, snapshot.damage)
        val oldImageIdentity = snapshot.state.resource(1u)!!.localIdentity
        val oldImageTarget = targets[0]

        val recreated = assertIs<RetainedDisplayApplyResult.Installed>(replica.apply(recreateImageDelta()))
        cache.install(recreated.state, recreated.damage)
        val newImageIdentity = recreated.state.resource(1u)!!.localIdentity

        assertNotEquals(oldImageIdentity, newImageIdentity)
        assertEquals(1, oldImageTarget.closes)
        assertNull(cache.texture(oldImageIdentity))
        assertSame(targets.last(), cache.texture(newImageIdentity))
        assertEquals(3, metrics.snapshot().textureCreations)
        assertEquals(1, metrics.snapshot().textureReleases)
    }

    @Test
    fun invalidationAndCloseReleaseEveryTextureExactlyOnce() {
        val targets = mutableListOf<RecordingTextureTarget>()
        val metrics = RetainedDisplayRenderMetrics()
        val cache = MinecraftRetainedTextureCache(recordingFactory(targets), metrics)
        val installed = assertIs<RetainedDisplayApplyResult.Installed>(RetainedDisplayReplica().apply(snapshot()))
        cache.install(installed.state, installed.damage)

        cache.invalidate()
        cache.close()

        assertEquals(listOf(1, 1), targets.map { it.closes })
        assertEquals(2, metrics.snapshot().textureReleases)
    }

    private fun recordingFactory(targets: MutableList<RecordingTextureTarget>) =
        MinecraftRetainedTextureTargetFactory { localIdentity, width, height, pixels ->
            RecordingTextureTarget(localIdentity, width, height, pixels).also(targets::add)
        }

    private class RecordingTextureTarget(
        localIdentity: Long,
        override val width: Int,
        override val height: Int,
        initialPixels: IntArray,
    ) : MinecraftRetainedTextureTarget {
        override val location: ResourceLocation =
            ResourceLocation.fromNamespaceAndPath("compukterkraft", "test/retained_$localIdentity")
        val pixels = initialPixels.copyOf()
        val patches = mutableListOf<RetainedPatchRectangle>()
        var closes = 0

        override fun patch(
            rectangle: RetainedPatchRectangle,
            argb: IntArray,
        ) {
            patches += rectangle
            for (row in 0 until rectangle.height) {
                for (column in 0 until rectangle.width) {
                    pixels[(rectangle.y + row) * width + rectangle.x + column] =
                        argb[row * rectangle.width + column]
                }
            }
        }

        override fun close() {
            closes += 1
        }
    }

    private fun snapshot(): ByteArray {
        val image = fullImage(1u, intArrayOf(0xf800, 0x07e0))
        val mask = fullMask(2u, byteArrayOf(0x80.toByte()))
        val drawList = emptyDrawList()
        return message(
            kind = 1,
            payload =
                bytes {
                    u64(1uL)
                    u32(2u)
                    u32(drawList.size.toUInt())
                    raw(image)
                    raw(mask)
                    raw(drawList)
                },
        )
    }

    private fun patchDelta(): ByteArray {
        val imagePatch =
            bytes {
                u16(0x0010)
                u16(0)
                u32(28u)
                u32(1u)
                u32(1u)
                u16(1)
                u16(0)
                u16(1)
                u16(1)
                u16(0x001f)
                u16(0)
            }
        val maskPatch =
            bytes {
                u16(0x0011)
                u16(0)
                u32(28u)
                u32(2u)
                u32(1u)
                u16(1)
                u16(0)
                u16(1)
                u16(1)
                raw(byteArrayOf(0x80.toByte(), 0, 0, 0))
            }
        return message(
            kind = 2,
            payload =
                bytes {
                    u64(1uL)
                    u64(2uL)
                    u32(2u)
                    u32(0u)
                    raw(imagePatch)
                    raw(maskPatch)
                },
        )
    }

    private fun recreateImageDelta(): ByteArray {
        val drop =
            bytes {
                u16(0x0020)
                u16(0)
                u32(12u)
                u32(1u)
            }
        val replacement = fullImage(1u, intArrayOf(0x001f, 0xffff))
        return message(
            kind = 2,
            payload =
                bytes {
                    u64(1uL)
                    u64(2uL)
                    u32(2u)
                    u32(0u)
                    raw(drop)
                    raw(replacement)
                },
        )
    }

    private fun fullImage(
        resourceId: UInt,
        pixels: IntArray,
    ): ByteArray =
        bytes {
            u16(1)
            u16(0)
            u32((16 + pixels.size * 2).toUInt())
            u32(resourceId)
            u16(pixels.size)
            u16(1)
            pixels.forEach(::u16)
        }

    private fun fullMask(
        resourceId: UInt,
        rows: ByteArray,
    ): ByteArray =
        bytes {
            u16(2)
            u16(0)
            u32((16 + rows.size).toUInt())
            u32(resourceId)
            u16(8)
            u16(1)
            raw(rows)
        }

    private fun emptyDrawList(): ByteArray =
        bytes {
            u16(0)
            u16(0)
            u32(0u)
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
