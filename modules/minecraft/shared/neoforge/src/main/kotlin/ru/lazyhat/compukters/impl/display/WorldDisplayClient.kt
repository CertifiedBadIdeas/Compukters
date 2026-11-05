/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.display

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import ru.lazyhat.compukters.minecraft.display.DisplayBlockEntity
import ru.lazyhat.compukters.minecraft.display.WorldDisplayTile
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One ordered, bounded image preparation lane; workers never access Minecraft or the level. */
internal object WorldDisplayClient {
    private var level: ClientLevel? = null
    private var executor: ThreadPoolExecutor? = null

    fun tick() {
        val current = Minecraft.getInstance().level
        if (level !== current) {
            executor?.shutdownNow()
            executor = null
            level = current
        }
    }

    fun receive(tile: WorldDisplayTile) {
        tick()
        val current = level ?: return
        val pos = BlockPos.of(tile.position)
        if (!current.hasChunkAt(pos)) return
        val entity = current.getBlockEntity(pos) as? DisplayBlockEntity ?: return
        val work = entity.prepareClientUpdate(tile) ?: return
        val worker =
            executor ?: ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64), { job ->
                Thread(job, "Compukters display decoder").apply { isDaemon = true }
            }).also { executor = it }
        try {
            worker.execute { work() }
        } catch (_: RejectedExecutionException) {
            entity.requireClientSnapshot(tile.publication)
        }
    }
}
