/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.peripheral

import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.RenderGuiEvent
import net.neoforged.neoforge.common.NeoForge
import ru.lazyhat.compukters.impl.compat.Identifier

internal object ConfiguratorOverlayBootstrap {
    private val texture = Identifier.fromNamespaceAndPath("compukters", "textures/misc/configurator_outline.png")

    fun register() {
        NeoForge.EVENT_BUS.addListener(ConfiguratorOverlayClient::tick)
        NeoForge.EVENT_BUS.addListener(::hud)
        NeoForge.EVENT_BUS.addListener(::extract)
        NeoForge.EVENT_BUS.addListener(::submit)
    }

    private data class Frame(
        val outlines: List<ru.lazyhat.compukters.minecraft.peripheral.ConfiguratorOutline>,
        val time: Double,
    )

    private val key =
        net.minecraft.util.context
            .ContextKey<Frame>(Identifier.fromNamespaceAndPath("compukters", "configurator_overlay"))

    private fun extract(event: net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent) {
        event.renderState.setRenderData(
            key,
            Frame(
                ConfiguratorOverlayClient.outlines(),
                event.renderTick + event.deltaTracker.getGameTimeDeltaPartialTick(false).toDouble(),
            ),
        )
    }

    private fun submit(event: net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent) {
        val frame = event.levelRenderState.getRenderData(key) ?: return
        if (frame.outlines.isEmpty()) return
        val camera = event.levelRenderState.cameraRenderState.pos
        val pose = event.poseStack
        pose.pushPose()
        val relative = frame.outlines.map { it.copy(bounds = it.bounds.move(-camera.x, -camera.y, -camera.z)) }
        event.submitNodeCollector.submitCustomGeometry(
            pose,
            net.minecraft.client.renderer.rendertype.RenderTypes
                .entityTranslucentEmissive(texture),
        ) { saved, vertices ->
            ConfiguratorOutlineMesh.draw(saved, vertices, relative, frame.time)
        }
        pose.popPose()
    }

    private fun hud(event: RenderGuiEvent.Post) {
        val minecraft = Minecraft.getInstance()
        if (minecraft.screen != null || minecraft.options.hideGui) return
        val graphics = event.guiGraphics
        val lines = ConfiguratorOverlayClient.labels().flatMap { minecraft.font.split(it, minOf(320, graphics.guiWidth() - 24)) }
        if (lines.isEmpty()) return
        val width = lines.maxOf(minecraft.font::width)
        val x = minOf(graphics.guiWidth() / 2 + 14, graphics.guiWidth() - width - 12).coerceAtLeast(12)
        var y = minOf(graphics.guiHeight() / 2 + 12, graphics.guiHeight() - lines.size * 12 - 12).coerceAtLeast(12)
        graphics.fill(x - 6, y - 5, x + width + 6, y + lines.size * 12, 0xBB101820.toInt())
        graphics.fill(x - 6, y - 5, x - 4, y + lines.size * 12, 0xFF6CDBFF.toInt())
        lines.forEach { line ->
            graphics.text(minecraft.font, line, x, y, 0xFFE0F3FF.toInt())
            y += 12
        }
    }
}
