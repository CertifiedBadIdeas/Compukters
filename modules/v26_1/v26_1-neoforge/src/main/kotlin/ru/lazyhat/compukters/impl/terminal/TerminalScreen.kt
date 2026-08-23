/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ru.lazyhat.compukters.impl.terminal

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import org.lwjgl.glfw.GLFW

class TerminalScreen(
    initial: TerminalSnapshotPayload,
) : Screen(Component.literal("Compukters terminal")) {
    val position = initial.position

    private var text = initial.text
    private var status = initial.status
    private var waitingForInput = initial.waitingForInput
    private var refreshCountdown = 0
    private lateinit var input: EditBox

    override fun init() {
        input =
            addRenderableWidget(
                EditBox(
                    font,
                    PADDING,
                    height - PADDING - INPUT_HEIGHT,
                    width - PADDING * 2,
                    INPUT_HEIGHT,
                    Component.literal("Program input"),
                ),
            )
        input.setMaxLength(TerminalInputPayload.MAXIMUM_INPUT_CODE_UNITS)
        updateInputState()
    }

    fun update(payload: TerminalSnapshotPayload) {
        text = payload.text
        status = payload.status
        waitingForInput = payload.waitingForInput
        if (::input.isInitialized) updateInputState()
    }

    override fun tick() {
        if (refreshCountdown-- <= 0) {
            ClientPacketDistributor.sendToServer(TerminalRefreshPayload(position))
            refreshCountdown = REFRESH_INTERVAL_TICKS
        }
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (waitingForInput && event.key() == GLFW.GLFW_KEY_ENTER) {
            ClientPacketDistributor.sendToServer(TerminalInputPayload(position, input.value))
            input.value = ""
            return true
        }
        return super.keyPressed(event)
    }

    override fun extractBackground(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        graphics.fill(0, 0, width, height, BACKGROUND_COLOR)
    }

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        graphics.text(font, title, PADDING, PADDING, TITLE_COLOR, true)
        graphics.text(font, status, PADDING, PADDING + font.lineHeight + 3, STATUS_COLOR, false)

        val outputTop = PADDING + font.lineHeight * 2 + 8
        val outputBottom = if (waitingForInput) height - PADDING - INPUT_HEIGHT - 6 else height - PADDING
        graphics.fill(PADDING, outputTop, width - PADDING, outputBottom, OUTPUT_BACKGROUND_COLOR)
        graphics.enableScissor(PADDING + 4, outputTop + 4, width - PADDING - 4, outputBottom - 4)
        val lines = font.split(Component.literal(text.ifEmpty { "(no output yet)" }), width - PADDING * 2 - 8)
        val visibleLineCount = ((outputBottom - outputTop - 8) / font.lineHeight).coerceAtLeast(0)
        lines.takeLast(visibleLineCount).forEachIndexed { index, line ->
            graphics.text(font, line, PADDING + 4, outputTop + 4 + index * font.lineHeight, TEXT_COLOR, false)
        }
        graphics.disableScissor()
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    override fun isPauseScreen(): Boolean = false

    private fun updateInputState() {
        input.visible = waitingForInput
        input.setEditable(waitingForInput)
        if (waitingForInput) setInitialFocus(input)
    }

    private companion object {
        const val PADDING = 16
        const val INPUT_HEIGHT = 20
        const val REFRESH_INTERVAL_TICKS = 4
        val BACKGROUND_COLOR = 0xFF101418.toInt()
        val OUTPUT_BACKGROUND_COLOR = 0xFF080B0D.toInt()
        val TITLE_COLOR = 0xFFF2F4F8.toInt()
        val STATUS_COLOR = 0xFF8BD5CA.toInt()
        val TEXT_COLOR = 0xFFD8DEE9.toInt()
    }
}
