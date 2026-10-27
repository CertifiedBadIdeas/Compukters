/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.display

import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent
import net.neoforged.neoforge.common.NeoForge
import ru.lazyhat.compukters.impl.compat.Identifier

internal object AttachedDisplayRenderer {
    private val key =
        net.minecraft.util.context.ContextKey<List<AttachedDisplayQuad>>(
            Identifier.fromNamespaceAndPath("compukters", "attached_displays"),
        )

    fun register() {
        NeoForge.EVENT_BUS.addListener(::extract)
        NeoForge.EVENT_BUS.addListener(::submit)
    }

    private fun extract(event: ExtractLevelRenderStateEvent) {
        event.renderState.setRenderData(key, AttachedDisplayClient.frame())
    }

    private fun submit(event: SubmitCustomGeometryEvent) {
        val frame = event.levelRenderState.getRenderData(key) ?: return
        val camera = event.levelRenderState.cameraRenderState.pos
        frame.forEach { quad ->
            event.submitNodeCollector.submitCustomGeometry(
                event.poseStack,
                net.minecraft.client.renderer.rendertype.RenderTypes
                    .entityTranslucentEmissive(quad.texture),
            ) { pose, vertices ->
                AttachedDisplayMesh.draw(pose, vertices, quad, camera)
            }
        }
    }
}
