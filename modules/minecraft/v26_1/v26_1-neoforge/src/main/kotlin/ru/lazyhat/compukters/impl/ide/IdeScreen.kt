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

package ru.lazyhat.compukters.impl.ide

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.joml.Matrix3x2fStack
import org.lwjgl.glfw.GLFW
import ru.lazyhat.compukters.impl.config.CompuktersClientConfig
import ru.lazyhat.compukters.impl.font.JetBrainsMonoRendering
import ru.lazyhat.compukters.impl.terminal.TerminalGridGeometry
import ru.lazyhat.compukters.impl.terminal.TerminalGridRenderer
import ru.lazyhat.compukters.impl.ui.CompuktersUiViewport
import ru.lazyhat.compukters.impl.ui.map
import ru.lazyhat.compukters.impl.ui.withTransform

internal fun <T> withIdeTextTransform(
    pose: Matrix3x2fStack,
    rotation: IdeTextRotation,
    x: Int,
    y: Int,
    draw: () -> T,
): T {
    if (rotation == IdeTextRotation.None) return draw()
    pose.pushMatrix()
    return try {
        pose.translate(x.toFloat(), y.toFloat())
        pose.rotate(CLOCKWISE_QUARTER_TURN)
        draw()
    } finally {
        pose.popMatrix()
    }
}

private val IDE_TOOLBAR_ICON_TEXTURE = Identifier.fromNamespaceAndPath("compukters", "textures/gui/ide_toolbar.png")

internal class IdeScreen(
    private val session: IdeClientSession<IdeClientApplication>,
    private val parent: Screen?,
) : Screen(Component.literal("Compukters IDE")) {
    private val editorKeyboardFocus = IdeEditorKeyboardFocus(font)
    private val ui =
        IdeScreenController(
            session.application.controller,
            session.application.preferences,
            session.application.targetTerminal,
            session.application.visibleLatency,
            object : IdeScreenHost {
                override var clipboard: String
                    get() = client().keyboardHandler.clipboard
                    set(value) {
                        client().keyboardHandler.clipboard = value
                    }

                override fun viewport(): CompuktersUiViewport = this@IdeScreen.viewport()

                override fun clearFocus() = this@IdeScreen.clearFocus()

                override fun restoreParent() {
                    val lifecycle = parent as? ChildScreenParent
                    client().setScreen(if (lifecycle == null || lifecycle.resumeFromChild()) parent else null)
                }

                override fun abandonParent() {
                    (parent as? ChildScreenParent)?.abandonChild()
                }

                override fun closeSession() = session.close()
            },
            peripheralRequest = ru.lazyhat.compukters.impl.ide.target.IdeTargetClientNetwork::namedPeripherals,
            peripheralInspection = ru.lazyhat.compukters.impl.ide.target.IdeTargetClientNetwork::inspectPeripherals,
            peripheralRename = ru.lazyhat.compukters.impl.ide.target.IdeTargetClientNetwork::renamePeripheral,
        )

    override fun setInitialFocus() = Unit

    override fun getFocused(): GuiEventListener? = editorKeyboardFocus.resolve(ui.focusArea, super.getFocused())

    override fun isPauseScreen(): Boolean = false

    override fun removed() {
        ui.removed()
        super.removed()
    }

    override fun tick() {
        ui.tick()
        super.tick()
    }

    private fun viewport(): CompuktersUiViewport {
        val window = client().window
        return CompuktersUiViewport.admitIde(window.width, window.height, window.guiScale, session.application.preferences.uiScale())
    }

    override fun mouseClicked(
        event: MouseButtonEvent,
        doubleClick: Boolean,
    ): Boolean = ui.mouseClicked(event.toIdeInput(), doubleClick) || super.mouseClicked(viewport().map(event), doubleClick)

    override fun mouseDragged(
        event: MouseButtonEvent,
        dragX: Double,
        dragY: Double,
    ): Boolean =
        ui.mouseDragged(event.toIdeInput()) ||
            super.mouseDragged(viewport().map(event), viewport().toVirtualDelta(dragX), viewport().toVirtualDelta(dragY))

    override fun mouseReleased(event: MouseButtonEvent): Boolean =
        ui.mouseReleased(event.toIdeInput()) || super.mouseReleased(viewport().map(event))

    override fun keyPressed(event: KeyEvent): Boolean = ui.keyPressed(event.toIdeInput()) || super.keyPressed(event)

    override fun keyReleased(event: KeyEvent): Boolean = ui.keyReleased(event.toIdeInput()) || super.keyReleased(event)

    override fun charTyped(event: CharacterEvent): Boolean =
        ui.charTyped(IdeCharacterInput(event.codepointAsString())) || super.charTyped(event)

    private fun client() = minecraft

    private fun MouseButtonEvent.toIdeInput(): IdePointerInput = IdePointerInput(x(), y(), button(), modifiers())

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        scrollX: Double,
        scrollY: Double,
    ): Boolean =
        ui.mouseScrolled(mouseX, mouseY, scrollX, scrollY) ||
            super.mouseScrolled(viewport().toVirtualX(mouseX), viewport().toVirtualY(mouseY), scrollX, scrollY)

    override fun mouseMoved(
        mouseX: Double,
        mouseY: Double,
    ) {
        ui.mouseMoved(mouseX, mouseY)
        super.mouseMoved(mouseX, mouseY)
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
        val frame = ui.frame(mouseX, mouseY)
        frame.viewport.withTransform(graphics.pose()) {
            IdeFramePainter.paint(frame, Canvas(graphics, frame.viewport), System.nanoTime() / 1_000_000L)
            super.extractRenderState(
                graphics,
                frame.viewport.toVirtualX(mouseX.toDouble()).toInt(),
                frame.viewport.toVirtualY(mouseY.toDouble()).toInt(),
                partialTick,
            )
        }
    }

    private inner class Canvas(
        private val graphics: GuiGraphicsExtractor,
        private val viewport: CompuktersUiViewport,
    ) : IdeCanvas {
        override fun layer(zIndex: Int) {
            graphics.nextStratum()
        }

        override fun fill(
            bounds: IdeRect,
            color: Int,
        ) {
            graphics.fill(bounds.left, bounds.top, bounds.right, bounds.bottom, color)
        }

        override fun icon(draw: IdeIconDraw) {
            draw.itemId?.let { id ->
                val item =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
                        ru.lazyhat.compukters.impl.compat.Identifier
                            .parse(id),
                    )
                val stack =
                    net.minecraft.world.item
                        .ItemStack(item)
                graphics.pose().pushMatrix()
                graphics.pose().translate(draw.bounds.left.toFloat(), draw.bounds.top.toFloat())
                val scale = minOf(draw.bounds.width, draw.bounds.height) / 16f
                graphics.pose().scale(scale, scale)
                graphics.item(stack, 0, 0)
                graphics.pose().popMatrix()
                return
            }

            val sprite = IdeToolbarIconAtlas.sprite(draw)
            graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                IDE_TOOLBAR_ICON_TEXTURE,
                sprite.bounds.left,
                sprite.bounds.top,
                sprite.sourceX.toFloat(),
                0f,
                sprite.bounds.width,
                sprite.bounds.height,
                IdeToolbarIconAtlas.CELL_SIZE,
                IdeToolbarIconAtlas.CELL_SIZE,
                IdeToolbarIconAtlas.WIDTH,
                IdeToolbarIconAtlas.HEIGHT,
                draw.color,
            )
        }

        override fun text(draw: IdeTextDraw) {
            draw.clip?.let { graphics.enableScissor(it.left, it.top, it.right, it.bottom) }
            withIdeTextTransform(graphics.pose(), draw.rotation, draw.x, draw.y) {
                val transformed = draw.rotation != IdeTextRotation.None
                val textX = if (transformed) 0 else draw.x
                val textY = if (transformed) 0 else draw.y
                val codeFont = draw.codeFont ?: IdeCodeFontProfile.DEFAULT
                IdeCodeGlyphLayout.layout(draw.value, textX, codeFont).forEach { glyph ->
                    val value =
                        Component
                            .literal(glyph.value)
                            .withStyle { style -> style.withFont(codeFont.fontDescription) }
                    JetBrainsMonoRendering.drawString(
                        graphics,
                        font,
                        value,
                        glyph.x,
                        textY,
                        draw.color,
                    )
                }
            }
            if (draw.clip != null) graphics.disableScissor()
        }

        override fun terminal(
            state: ru.lazyhat.compukters.lang.runtime.vm.TerminalState,
            geometry: TerminalGridGeometry,
            nowMillis: Long,
        ) {
            TerminalGridRenderer.draw(graphics, font, state, geometry, nowMillis)
        }
    }
}

private val CLOCKWISE_QUARTER_TURN = (Math.PI / 2.0).toFloat()

private fun KeyEvent.toIdeInput(): IdeKeyInput =
    IdeKeyInput(
        key = key(),
        modifiers = modifiers(),
        paste = isPaste,
    )
