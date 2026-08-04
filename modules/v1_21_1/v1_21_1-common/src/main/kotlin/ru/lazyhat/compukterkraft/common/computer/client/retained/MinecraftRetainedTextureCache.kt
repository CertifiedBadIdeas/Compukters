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

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.renderer.texture.TextureManager
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.FastColor
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedDisplayInstallDamage
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedDisplayResource
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedDisplayResourceEntry
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedDisplayState
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedImageRgb565
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedMask1Bpp
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedPatchRectangle
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedResourceDamage
import ru.lazyhat.compukterkraft.core.device.display.retained.render.retainedRgb565ToArgb

fun interface MinecraftRetainedTextureTargetFactory {
    fun create(
        localIdentity: Long,
        width: Int,
        height: Int,
        initialArgb: IntArray,
    ): MinecraftRetainedTextureTarget
}

interface MinecraftRetainedTextureTarget : AutoCloseable {
    val location: ResourceLocation
    val width: Int
    val height: Int

    fun patch(
        rectangle: RetainedPatchRectangle,
        argb: IntArray,
    )
}

class MinecraftRetainedTextureCache(
    private val targetFactory: MinecraftRetainedTextureTargetFactory,
    private val metrics: RetainedDisplayRenderMetrics,
) : AutoCloseable {
    private val textures = mutableMapOf<Long, MinecraftRetainedTextureTarget>()

    fun texture(localIdentity: Long): MinecraftRetainedTextureTarget? = textures[localIdentity]

    fun install(
        state: RetainedDisplayState,
        damage: RetainedDisplayInstallDamage,
    ) {
        when (damage) {
            RetainedDisplayInstallDamage.FullReplacement -> {
                releaseAll()
                state.resources.forEach(::createTextureIfNeeded)
            }

            is RetainedDisplayInstallDamage.Delta -> {
                for (change in damage.resourceChanges) {
                    when (change) {
                        is RetainedResourceDamage.Created -> {
                            val entry = requireResource(state, change.resourceId, change.localIdentity)
                            createTextureIfNeeded(entry)
                        }

                        is RetainedResourceDamage.Dropped -> {
                            release(change.localIdentity)
                        }

                        is RetainedResourceDamage.ImagePatched -> {
                            val entry = requireResource(state, change.resourceId, change.localIdentity)
                            val image =
                                entry.content as? RetainedImageRgb565
                                    ?: error("Retained image damage does not resolve to an image")
                            patchTexture(change.localIdentity, change.rectangles) { x, y ->
                                retainedRgb565ToArgb(image.pixelAt(x, y))
                            }
                        }

                        is RetainedResourceDamage.MaskPatched -> {
                            val entry = requireResource(state, change.resourceId, change.localIdentity)
                            val mask =
                                entry.content as? RetainedMask1Bpp
                                    ?: error("Retained mask damage does not resolve to a mask")
                            patchTexture(change.localIdentity, change.rectangles) { x, y -> maskArgb(mask.bitAt(x, y)) }
                        }

                        is RetainedResourceDamage.InstancesPatched -> {
                            Unit
                        }
                    }
                }
            }
        }
    }

    fun invalidate() {
        releaseAll()
    }

    override fun close() {
        releaseAll()
    }

    private fun createTextureIfNeeded(entry: RetainedDisplayResourceEntry) {
        val textureData = textureData(entry.content) ?: return
        check(entry.localIdentity !in textures) { "Retained texture identity is already installed: ${entry.localIdentity}" }
        val target = targetFactory.create(entry.localIdentity, textureData.width, textureData.height, textureData.argb)
        try {
            check(target.width == textureData.width && target.height == textureData.height) {
                "Retained texture target dimensions do not match resource ${entry.resourceId}"
            }
        } catch (failure: Throwable) {
            try {
                target.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
        textures[entry.localIdentity] = target
        metrics.recordTextureCreation(textureData.argb.size)
    }

    private fun patchTexture(
        localIdentity: Long,
        rectangles: List<RetainedPatchRectangle>,
        pixel: (Int, Int) -> Int,
    ) {
        val target = textures[localIdentity] ?: error("Retained texture identity is not installed: $localIdentity")
        for (rectangle in rectangles) {
            val argb =
                IntArray(rectangle.width * rectangle.height) { index ->
                    val x = rectangle.x + index % rectangle.width
                    val y = rectangle.y + index / rectangle.width
                    pixel(x, y)
                }
            target.patch(rectangle, argb)
            metrics.recordSubrectangleUpload(argb.size)
        }
    }

    private fun textureData(resource: RetainedDisplayResource): TextureData? =
        when (resource) {
            is RetainedImageRgb565 -> {
                TextureData(
                    resource.width,
                    resource.height,
                    IntArray(resource.width * resource.height) { index ->
                        retainedRgb565ToArgb(resource.pixelAt(index % resource.width, index / resource.width))
                    },
                )
            }

            is RetainedMask1Bpp -> {
                TextureData(
                    resource.width,
                    resource.height,
                    IntArray(resource.width * resource.height) { index ->
                        maskArgb(resource.bitAt(index % resource.width, index / resource.width))
                    },
                )
            }

            else -> {
                null
            }
        }

    private fun requireResource(
        state: RetainedDisplayState,
        resourceId: UInt,
        localIdentity: Long,
    ): RetainedDisplayResourceEntry {
        val entry = state.resource(resourceId)
        check(entry != null && entry.localIdentity == localIdentity) {
            "Retained texture damage does not resolve to installed resource $resourceId/$localIdentity"
        }
        return entry
    }

    private fun release(localIdentity: Long) {
        textures.remove(localIdentity)?.let {
            it.close()
            metrics.recordTextureRelease()
        }
    }

    private fun releaseAll() {
        val closing = textures.values.toList()
        textures.clear()
        cleanupAll(closing) {
            it.close()
            metrics.recordTextureRelease()
        }
    }

    private data class TextureData(
        val width: Int,
        val height: Int,
        val argb: IntArray,
    )

    private companion object {
        fun maskArgb(set: Boolean): Int = if (set) 0xffff_ffff.toInt() else 0x00ff_ffff
    }
}

class NativeImageRetainedTextureTargetFactory(
    private val textureManager: TextureManager,
    private val namePrefix: String,
) : MinecraftRetainedTextureTargetFactory {
    override fun create(
        localIdentity: Long,
        width: Int,
        height: Int,
        initialArgb: IntArray,
    ): MinecraftRetainedTextureTarget {
        require(initialArgb.size == width * height)
        val image = NativeImage(width, height, false)
        var texture: DynamicTexture? = null
        try {
            initialArgb.forEachIndexed { index, argb ->
                image.setPixelRGBA(index % width, index / width, FastColor.ABGR32.fromArgb32(argb))
            }
            val createdTexture = DynamicTexture(image).apply { setFilter(false, false) }
            texture = createdTexture
            val location = textureManager.register("${namePrefix}_$localIdentity", createdTexture)
            return NativeImageRetainedTextureTarget(textureManager, location, createdTexture, image, width, height)
        } catch (failure: Throwable) {
            try {
                texture?.close() ?: image.close()
            } catch (closeFailure: Throwable) {
                if (failure !== closeFailure) failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }
}

private class NativeImageRetainedTextureTarget(
    private val textureManager: TextureManager,
    override val location: ResourceLocation,
    private val texture: DynamicTexture,
    private val image: NativeImage,
    override val width: Int,
    override val height: Int,
) : MinecraftRetainedTextureTarget {
    private var closed = false

    override fun patch(
        rectangle: RetainedPatchRectangle,
        argb: IntArray,
    ) {
        check(!closed) { "Retained texture target is closed" }
        require(argb.size == rectangle.width * rectangle.height)
        argb.forEachIndexed { index, pixel ->
            image.setPixelRGBA(
                rectangle.x + index % rectangle.width,
                rectangle.y + index / rectangle.width,
                FastColor.ABGR32.fromArgb32(pixel),
            )
        }
        texture.bind()
        image.upload(
            0,
            rectangle.x,
            rectangle.y,
            rectangle.x,
            rectangle.y,
            rectangle.width,
            rectangle.height,
            false,
            false,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        textureManager.release(location)
    }
}
