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

import net.minecraft.client.renderer.texture.TextureManager
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedDisplayInstallDamage
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedDisplayState

class MinecraftRetainedDisplayCache(
    private val textureCache: MinecraftRetainedTextureCache,
    private val batchCacheFactory: (RetainedDisplayViewKind) -> MinecraftRetainedBatchCache,
) : RetainedDisplayNativeCache {
    private val views = mutableMapOf<RetainedDisplayViewKind, ViewCache>()
    private var currentState: RetainedDisplayState? = null

    override fun retainView(
        viewKind: RetainedDisplayViewKind,
        state: RetainedDisplayState?,
    ) {
        val existing = views[viewKind]
        if (existing != null) {
            existing.references += 1
            return
        }
        val installed = currentState
        check(state == null || installed?.sameRevision(state) == true) {
            "Late retained view does not match the native cache revision"
        }
        val cache = batchCacheFactory(viewKind)
        try {
            if (installed != null) cache.install(installed, RetainedDisplayInstallDamage.FullReplacement)
        } catch (failure: Throwable) {
            cache.close()
            throw failure
        }
        views[viewKind] = ViewCache(1, cache)
    }

    override fun releaseView(viewKind: RetainedDisplayViewKind) {
        val view = checkNotNull(views[viewKind]) { "Retained display view is not retained: $viewKind" }
        check(view.references > 0)
        view.references -= 1
        if (view.references == 0) {
            views.remove(viewKind)
            view.cache.close()
        }
    }

    override fun install(
        state: RetainedDisplayState,
        damage: RetainedDisplayInstallDamage,
    ) {
        textureCache.install(state, damage)
        views.values.forEach { it.cache.install(state, damage) }
        currentState = state
    }

    override fun presentation(viewKind: RetainedDisplayViewKind): MinecraftRetainedNativePresentation? =
        views[viewKind]?.cache?.presentationOrNull()

    override fun invalidate() {
        currentState = null
        val caches = views.values.map { it.cache }
        var failure: Throwable? = null
        try {
            cleanupAll(caches, MinecraftRetainedBatchCache::invalidate)
        } catch (caught: Throwable) {
            failure = caught
        }
        try {
            textureCache.invalidate()
        } catch (caught: Throwable) {
            if (failure == null) {
                failure = caught
            } else if (failure !== caught) {
                failure.addSuppressed(caught)
            }
        }
        failure?.let { throw it }
    }

    override fun close() {
        val closing = views.values.toList()
        views.clear()
        currentState = null
        var failure: Throwable? = null
        try {
            cleanupAll(closing) { it.cache.close() }
        } catch (caught: Throwable) {
            failure = caught
        }
        try {
            textureCache.close()
        } catch (caught: Throwable) {
            if (failure == null) {
                failure = caught
            } else if (failure !== caught) {
                failure.addSuppressed(caught)
            }
        }
        failure?.let { throw it }
    }

    private fun RetainedDisplayState.sameRevision(other: RetainedDisplayState): Boolean =
        computerId == other.computerId && viewerEpoch == other.viewerEpoch && sequence == other.sequence

    private data class ViewCache(
        var references: Int,
        val cache: MinecraftRetainedBatchCache,
    )

    companion object {
        fun create(
            textureManager: TextureManager,
            computerId: UInt,
            metrics: RetainedDisplayRenderMetrics = RetainedDisplayRenderMetrics(),
        ): MinecraftRetainedDisplayCache {
            val textureCache =
                MinecraftRetainedTextureCache(
                    NativeImageRetainedTextureTargetFactory(textureManager, "compukterkraft_retained_$computerId"),
                    metrics,
                )
            return MinecraftRetainedDisplayCache(
                textureCache,
                batchCacheFactory = { viewKind ->
                    val flavor =
                        when (viewKind) {
                            RetainedDisplayViewKind.MENU -> RetainedBatchRenderFlavor.MENU
                            RetainedDisplayViewKind.WORLD -> RetainedBatchRenderFlavor.WORLD_EMISSIVE
                        }
                    MinecraftRetainedBatchCache(
                        NativeVertexBufferRetainedBatchTargetFactory(textureCache, flavor),
                        metrics,
                    )
                },
            )
        }
    }
}
