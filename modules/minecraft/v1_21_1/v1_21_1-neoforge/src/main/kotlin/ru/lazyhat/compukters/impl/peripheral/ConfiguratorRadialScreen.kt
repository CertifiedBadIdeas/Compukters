/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.peripheral

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import ru.lazyhat.compukters.impl.ide.IdeColors

/** Version adapter; sector hit testing and raster geometry are shared. */
internal class ConfiguratorRadialScreen : Screen(Component.translatable("screen.compukters.configurator.radial")) {
    override fun isPauseScreen(): Boolean = false

    override fun init() {
        val window = Minecraft.getInstance().window
        GLFW.glfwSetCursorPos(ConfiguratorWindow.handle(), window.width / 2.0, window.height / 2.0)
    }

    override fun tick() {
        val client = Minecraft.getInstance()
        if (!ConfiguratorRadialClient.valid() || !client.isWindowActive) {
            onClose()
            return
        }
        if (GLFW.glfwGetMouseButton(ConfiguratorWindow.handle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) != GLFW.GLFW_PRESS) {
            val x = client.mouseHandler.xpos() * width / client.window.screenWidth
            val y = client.mouseHandler.ypos() * height / client.window.screenHeight
            ConfiguratorRadialClient.choose(ConfiguratorRadialGeometry.select(x - width / 2.0, y - height / 2.0))
            client.setScreen(null)
        }
    }

    override fun removed() {
        ConfiguratorRadialClient.cancel()
        super.removed()
    }

    override fun renderBackground(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        graphics.fill(0, 0, width, height, IdeColors.DIM)
    }

    override fun render(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        val cx = width / 2
        val cy = height / 2
        val selected = ConfiguratorRadialGeometry.select((mouseX - cx).toDouble(), (mouseY - cy).toDouble())
        ConfiguratorRadialGeometry.spans.forEach { span ->
            val color = if (selected == span.sector) IdeColors.SELECTION else IdeColors.PANEL_ALT
            graphics.fill(cx + span.x, cy + span.y, cx + span.x + span.width, cy + span.y + 1, color)
        }

        fun label(
            text: Component,
            x: Int,
            y: Int,
            color: Int,
        ) {
            graphics.drawString(font, text, x - font.width(text) / 2, y - font.lineHeight / 2, color, false)
        }
        ConfiguratorRadialGeometry.modes.forEachIndexed { index, mode ->
            label(
                Component.translatable("screen.compukters.configurator.radial.$mode"),
                cx + ConfiguratorRadialGeometry.labelX(index),
                cy + ConfiguratorRadialGeometry.labelY(index),
                if (index == selected) IdeColors.ACCENT else IdeColors.TEXT,
            )
        }
        label(Component.translatable("gui.cancel"), cx, cy, IdeColors.MUTED)
        label(title, cx, cy - ConfiguratorRadialGeometry.OUTER - 20, IdeColors.TEXT)
        label(
            Component.translatable("screen.compukters.configurator.radial.hint"),
            cx,
            cy + ConfiguratorRadialGeometry.OUTER + 20,
            IdeColors.MUTED,
        )
    }
}
