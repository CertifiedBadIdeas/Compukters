/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.display

import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.common.NeoForge

internal object AttachedDisplayRenderer {
    fun register() {
        NeoForge.EVENT_BUS.addListener(::render)
    }

    private fun render(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return
        val frame = AttachedDisplayClient.frame()
        val buffers = Minecraft.getInstance().renderBuffers().bufferSource()
        frame.groupBy { it.texture }.forEach { (texture, quads) ->
            val type =
                net.minecraft.client.renderer.RenderType
                    .entityTranslucentEmissive(texture)
            val vertices = buffers.getBuffer(type)
            quads.forEach { AttachedDisplayMesh.draw(event.poseStack.last(), vertices, it, event.camera.position) }
            buffers.endBatch(type)
        }
    }
}
