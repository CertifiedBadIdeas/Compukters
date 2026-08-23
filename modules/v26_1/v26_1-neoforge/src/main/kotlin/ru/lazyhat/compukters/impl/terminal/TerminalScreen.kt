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
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.resources.Identifier
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import org.lwjgl.glfw.GLFW
import ru.lazyhat.compukters.lang.runtime.vm.TerminalCell
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier

class TerminalScreen(
    initial: TerminalFullPayload,
) : Screen(Component.literal("Compukters terminal")) {
    val position = initial.position

    private val replica = TerminalReplica(initial)
    private val pressedKeys = mutableSetOf<Int>()

    fun update(payload: TerminalFullPayload): Boolean = replica.replace(payload)

    fun update(payload: TerminalDeltaPayload): Boolean = replica.apply(payload)

    fun requestResync() {
        ClientPacketDistributor.sendToServer(TerminalResyncPayload(position, replica.machineId, replica.state.revision))
    }

    override fun removed() {
        ClientPacketDistributor.sendToServer(TerminalClosePayload(position, replica.machineId))
        pressedKeys.clear()
        super.removed()
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.isPaste) {
            val pasted = boundedText(minecraft.keyboardHandler.clipboard)
            if (pasted.isNotEmpty()) {
                sendText(pasted)
            }
            return true
        }
        val key = KEY_MAP[event.key()] ?: return super.keyPressed(event)
        val action = if (pressedKeys.add(event.key())) TerminalKeyAction.PRESS else TerminalKeyAction.REPEAT
        ClientPacketDistributor.sendToServer(
            TerminalKeyPayload(position, replica.machineId, key, action, modifiers(event.modifiers())),
        )
        return if (key == TerminalKey.ESCAPE) super.keyPressed(event) else true
    }

    override fun keyReleased(event: KeyEvent): Boolean {
        val mapped = KEY_MAP.containsKey(event.key())
        pressedKeys.remove(event.key())
        return mapped || super.keyReleased(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        val text = event.codepointAsString()
        sendText(text)
        return true
    }

    override fun extractBackground(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        graphics.fill(0, 0, width, height, DIM_COLOR)
    }

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        val geometry = TerminalRenderGeometry(width, height)
        graphics.fill(
            geometry.panel.left - 1,
            geometry.panel.top - 1,
            geometry.panel.right + 1,
            geometry.panel.bottom + 1,
            PANEL_BORDER_COLOR,
        )
        graphics.fill(
            geometry.panel.left,
            geometry.panel.top,
            geometry.panel.right,
            geometry.panel.bottom,
            PANEL_COLOR,
        )
        graphics.text(font, title, geometry.titleX, geometry.titleY, TITLE_COLOR, false)
        graphics.fill(
            geometry.grid.left,
            geometry.grid.top - 1,
            geometry.grid.right,
            geometry.grid.bottom,
            GRID_COLOR,
        )
        drawBackgroundRuns(graphics, geometry)
        drawGlyphs(graphics, geometry)
        if (TerminalRenderGeometry.drawCursor(replica.state.cursorVisible, System.nanoTime() / 1_000_000L)) {
            val cursor = geometry.cursor(replica.state.cursor)
            graphics.fill(cursor.left, cursor.top, cursor.right, cursor.bottom, CURSOR_COLOR)
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    override fun isPauseScreen(): Boolean = false

    private fun drawBackgroundRuns(
        graphics: GuiGraphicsExtractor,
        geometry: TerminalRenderGeometry,
    ) {
        repeat(replica.state.height) { y ->
            var start = 0
            while (start < replica.state.width) {
                val background = cell(start, y).background
                var end = start + 1
                while (end < replica.state.width && cell(end, y).background == background) end++
                if (background != 0) {
                    val first = geometry.cell(start, y)
                    val last = geometry.cell(end - 1, y)
                    graphics.fill(first.left, first.top, last.right, first.bottom, TerminalRenderGeometry.paletteColor(background))
                }
                start = end
            }
        }
    }

    private fun drawGlyphs(
        graphics: GuiGraphicsExtractor,
        geometry: TerminalRenderGeometry,
    ) {
        repeat(replica.state.height) { y ->
            repeat(replica.state.width) cellLoop@{ x ->
                val cell = cell(x, y)
                if (cell.codePoint == ' '.code) return@cellLoop
                val glyph =
                    Component
                        .literal(String(Character.toChars(cell.codePoint)))
                        .withStyle { style ->
                            style
                                .withFont(UNIFORM_FONT)
                                .withColor(TerminalRenderGeometry.paletteColor(cell.foreground))
                        }
                val bounds = geometry.cell(x, y)
                val clip = geometry.glyphClip(x, y)
                val glyphX = bounds.left + (bounds.width - font.width(glyph)) / 2
                val glyphY = bounds.top + (bounds.height - font.lineHeight) / 2
                graphics.enableScissor(clip.left, clip.top, clip.right, clip.bottom)
                graphics.text(font, glyph, glyphX, glyphY, TerminalRenderGeometry.paletteColor(cell.foreground), false)
                graphics.disableScissor()
            }
        }
    }

    private fun cell(
        x: Int,
        y: Int,
    ): TerminalCell = replica.state.cells[y * replica.state.width + x]

    private fun sendText(text: String) {
        ClientPacketDistributor.sendToServer(TerminalTextPayload(position, replica.machineId, text))
    }

    private fun boundedText(
        value: String,
        maximumCodeUnits: Int = TerminalProtocol.MAXIMUM_TEXT_CODE_UNITS,
    ): String {
        val result = StringBuilder(minOf(value.length, maximumCodeUnits))
        var offset = 0
        while (offset < value.length) {
            val first = value[offset]
            val validPair =
                Character.isHighSurrogate(first) &&
                    offset + 1 < value.length &&
                    Character.isLowSurrogate(value[offset + 1])
            val codePoint =
                when {
                    validPair -> Character.toCodePoint(first, value[offset + 1])
                    Character.isSurrogate(first) -> 0xFFFD
                    else -> first.code
                }
            val inputUnits = if (validPair) 2 else 1
            val outputUnits = Character.charCount(codePoint)
            if (result.length + outputUnits > maximumCodeUnits) break
            result.appendCodePoint(codePoint)
            offset += inputUnits
        }
        return result.toString()
    }

    private fun modifiers(bits: Int): Set<TerminalModifier> =
        buildSet {
            if (bits and GLFW.GLFW_MOD_SHIFT != 0) add(TerminalModifier.SHIFT)
            if (bits and GLFW.GLFW_MOD_CONTROL != 0) add(TerminalModifier.CONTROL)
            if (bits and GLFW.GLFW_MOD_ALT != 0) add(TerminalModifier.ALT)
            if (bits and GLFW.GLFW_MOD_SUPER != 0) add(TerminalModifier.SUPER)
        }

    private companion object {
        val UNIFORM_FONT = FontDescription.Resource(Identifier.withDefaultNamespace("uniform"))
        val DIM_COLOR = 0x99000000.toInt()
        val PANEL_COLOR = 0xFF101418.toInt()
        val PANEL_BORDER_COLOR = 0xFF27323A.toInt()
        val TITLE_COLOR = 0xFFF2F4F8.toInt()
        val GRID_COLOR = TerminalRenderGeometry.paletteColor(0)
        val CURSOR_COLOR = 0xFFFFFFFF.toInt()
        val KEY_MAP =
            mapOf(
                GLFW.GLFW_KEY_ESCAPE to TerminalKey.ESCAPE,
                GLFW.GLFW_KEY_BACKSPACE to TerminalKey.BACKSPACE,
                GLFW.GLFW_KEY_TAB to TerminalKey.TAB,
                GLFW.GLFW_KEY_ENTER to TerminalKey.ENTER,
                GLFW.GLFW_KEY_INSERT to TerminalKey.INSERT,
                GLFW.GLFW_KEY_DELETE to TerminalKey.DELETE,
                GLFW.GLFW_KEY_HOME to TerminalKey.HOME,
                GLFW.GLFW_KEY_END to TerminalKey.END,
                GLFW.GLFW_KEY_PAGE_UP to TerminalKey.PAGE_UP,
                GLFW.GLFW_KEY_PAGE_DOWN to TerminalKey.PAGE_DOWN,
                GLFW.GLFW_KEY_UP to TerminalKey.UP,
                GLFW.GLFW_KEY_LEFT to TerminalKey.LEFT,
                GLFW.GLFW_KEY_DOWN to TerminalKey.DOWN,
                GLFW.GLFW_KEY_RIGHT to TerminalKey.RIGHT,
                GLFW.GLFW_KEY_F1 to TerminalKey.F1,
                GLFW.GLFW_KEY_F2 to TerminalKey.F2,
                GLFW.GLFW_KEY_F3 to TerminalKey.F3,
                GLFW.GLFW_KEY_F4 to TerminalKey.F4,
                GLFW.GLFW_KEY_F5 to TerminalKey.F5,
                GLFW.GLFW_KEY_F6 to TerminalKey.F6,
                GLFW.GLFW_KEY_F7 to TerminalKey.F7,
                GLFW.GLFW_KEY_F8 to TerminalKey.F8,
                GLFW.GLFW_KEY_F9 to TerminalKey.F9,
                GLFW.GLFW_KEY_F10 to TerminalKey.F10,
                GLFW.GLFW_KEY_F11 to TerminalKey.F11,
                GLFW.GLFW_KEY_F12 to TerminalKey.F12,
            )
    }
}
