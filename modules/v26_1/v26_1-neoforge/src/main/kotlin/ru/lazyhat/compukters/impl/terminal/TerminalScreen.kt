/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.impl.terminal

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import org.lwjgl.glfw.GLFW
import ru.lazyhat.compukters.impl.config.CompuktersClientConfig
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
    private var fontProfile = CompuktersClientConfig.selectedFont()
    private lateinit var fontButton: Button

    override fun init() {
        super.init()
        val bounds = TerminalRenderGeometry(width, height, fontProfile).fontButton
        fontButton =
            addRenderableWidget(
                Button
                    .builder(fontButtonLabel()) { cycleFont() }
                    .bounds(bounds.left, bounds.top, bounds.width, bounds.height)
                    .build(),
            )
    }

    override fun setInitialFocus() = Unit

    override fun mouseClicked(
        event: MouseButtonEvent,
        doubleClick: Boolean,
    ): Boolean {
        val handled = super.mouseClicked(event, doubleClick)
        if (handled) clearFocus()
        return handled
    }

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
        val geometry = TerminalRenderGeometry(width, height, fontProfile)
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

    private fun cycleFont() {
        fontProfile = fontProfile.next()
        CompuktersClientConfig.selectFont(fontProfile)
        fontButton.message = fontButtonLabel()
        positionFontButton()
    }

    private fun positionFontButton() {
        val bounds = TerminalRenderGeometry(width, height, fontProfile).fontButton
        fontButton.x = bounds.left
        fontButton.y = bounds.top
    }

    private fun fontButtonLabel(): Component = Component.literal("Font: ${fontProfile.displayName}")

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
                val renderedCodePoint = fontProfile.renderCodePoint(cell.codePoint)
                val glyph =
                    Component
                        .literal(String(Character.toChars(renderedCodePoint)))
                        .withStyle { style ->
                            style
                                .withFont(fontProfile.fontDescription)
                                .withColor(TerminalRenderGeometry.paletteColor(cell.foreground))
                        }
                val bounds = geometry.cell(x, y)
                val clip = geometry.glyphClip(x, y)
                val glyphX = bounds.left
                val glyphY = bounds.top + fontProfile.glyphDrawOffsetY
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
