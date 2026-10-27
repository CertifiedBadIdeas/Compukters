/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.peripheral

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
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

    override fun extractBackground(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        graphics.fill(0, 0, width, height, IdeColors.DIM)
    }

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        ConfiguratorRadialPresentation.draw(
            width,
            height,
            mouseX,
            mouseY,
            font::width,
            font.lineHeight,
            { left, top, right, bottom, color -> graphics.fill(left, top, right, bottom, color) },
            { text, x, y, color -> graphics.text(font, text, x, y, color, false) },
        )
    }
}
