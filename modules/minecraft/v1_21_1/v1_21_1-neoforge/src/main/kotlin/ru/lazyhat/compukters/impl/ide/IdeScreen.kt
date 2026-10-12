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

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import org.lwjgl.glfw.GLFW
import ru.lazyhat.compukters.impl.config.CompuktersClientConfig
import ru.lazyhat.compukters.impl.font.JetBrainsMonoRendering
import ru.lazyhat.compukters.impl.terminal.TerminalGridGeometry
import ru.lazyhat.compukters.impl.terminal.TerminalGridRenderer
import ru.lazyhat.compukters.impl.ui.CompuktersUiViewport
import ru.lazyhat.compukters.impl.ui.withTransform
import kotlin.math.ceil
import kotlin.math.floor

internal fun <T> withIdeTextTransform(
    pose: PoseStack,
    rotation: IdeTextRotation,
    x: Int,
    y: Int,
    draw: () -> T,
): T {
    if (rotation == IdeTextRotation.None) return draw()
    pose.pushPose()
    return try {
        pose.translate(x.toFloat(), y.toFloat(), 0f)
        pose.mulPose(Axis.ZP.rotationDegrees(CLOCKWISE_QUARTER_TURN_DEGREES))
        draw()
    } finally {
        pose.popPose()
    }
}

private val IDE_TOOLBAR_ICON_TEXTURE = ResourceLocation.fromNamespaceAndPath("compukters", "textures/gui/ide_toolbar.png")

internal class IdeScreen(
    private val session: IdeClientSession<IdeClientApplication>,
    private val parent: Screen?,
) : Screen(Component.literal("Compukters IDE")) {
    private lateinit var editorKeyboardFocus: IdeEditorKeyboardFocus
    private val clicks = IdeClickTracker()
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
        return CompuktersUiViewport.admitIde(
            window.width,
            window.height,
            window.guiScale.toInt(),
            session.application.preferences.uiScale(),
        )
    }

    override fun init() {
        super.init()
        editorKeyboardFocus = IdeEditorKeyboardFocus(font)
        client().textureManager.getTexture(IDE_TOOLBAR_ICON_TEXTURE).setFilter(true, false)
    }

    override fun mouseClicked(
        mouseX: Double,
        mouseY: Double,
        button: Int,
    ): Boolean {
        val viewport = viewport()
        return ui.mouseClicked(
            IdePointerInput(mouseX, mouseY, button, currentModifiers()),
            clicks.click(viewport.toVirtualX(mouseX), viewport.toVirtualY(mouseY), button, System.nanoTime()),
        ) ||
            super.mouseClicked(viewport.toVirtualX(mouseX), viewport.toVirtualY(mouseY), button)
    }

    override fun mouseDragged(
        mouseX: Double,
        mouseY: Double,
        button: Int,
        dragX: Double,
        dragY: Double,
    ): Boolean =
        ui.mouseDragged(IdePointerInput(mouseX, mouseY, button, currentModifiers())) ||
            super.mouseDragged(
                viewport().toVirtualX(mouseX),
                viewport().toVirtualY(mouseY),
                button,
                viewport().toVirtualDelta(dragX),
                viewport().toVirtualDelta(dragY),
            )

    override fun mouseReleased(
        mouseX: Double,
        mouseY: Double,
        button: Int,
    ): Boolean =
        ui.mouseReleased(IdePointerInput(mouseX, mouseY, button, currentModifiers())) ||
            super.mouseReleased(viewport().toVirtualX(mouseX), viewport().toVirtualY(mouseY), button)

    override fun keyPressed(
        keyCode: Int,
        scanCode: Int,
        modifiers: Int,
    ): Boolean = ui.keyPressed(IdeKeyInput(keyCode, modifiers, isPaste(keyCode))) || super.keyPressed(keyCode, scanCode, modifiers)

    override fun keyReleased(
        keyCode: Int,
        scanCode: Int,
        modifiers: Int,
    ): Boolean = ui.keyReleased(IdeKeyInput(keyCode, modifiers)) || super.keyReleased(keyCode, scanCode, modifiers)

    override fun charTyped(
        codePoint: Char,
        modifiers: Int,
    ): Boolean = ui.charTyped(IdeCharacterInput(codePoint.toString())) || super.charTyped(codePoint, modifiers)

    private fun client() = requireNotNull(minecraft) { "IDE screen is not attached to a Minecraft client" }

    private fun currentModifiers(): Int {
        var modifiers = 0
        if (hasShiftDown()) modifiers = modifiers or GLFW.GLFW_MOD_SHIFT
        if (hasControlDown()) modifiers = modifiers or GLFW.GLFW_MOD_CONTROL
        if (hasAltDown()) modifiers = modifiers or GLFW.GLFW_MOD_ALT
        return modifiers
    }

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

    override fun renderBackground(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) = Unit

    override fun render(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        graphics.fill(0, 0, width, height, IdeColors.DIM)
        val frame = ui.frame(mouseX, mouseY)
        frame.viewport.withTransform(graphics.pose()) {
            IdeFramePainter.paint(frame, Canvas(graphics, frame.viewport), System.nanoTime() / 1_000_000L)
            super.render(
                graphics,
                frame.viewport.toVirtualX(mouseX.toDouble()).toInt(),
                frame.viewport.toVirtualY(mouseY.toDouble()).toInt(),
                partialTick,
            )
        }
    }

    private inner class Canvas(
        private val graphics: GuiGraphics,
        private val viewport: CompuktersUiViewport,
    ) : IdeCanvas {
        override fun fill(
            bounds: IdeRect,
            color: Int,
        ) {
            graphics.fill(bounds.left, bounds.top, bounds.right, bounds.bottom, color)
        }

        override fun icon(draw: IdeIconDraw) {
            draw.itemId?.let { id ->
                val item =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .get(
                            ru.lazyhat.compukters.impl.compat.Identifier
                                .parse(id),
                        )
                val stack =
                    net.minecraft.world.item
                        .ItemStack(item)
                graphics.pose().pushPose()
                graphics.pose().translate(draw.bounds.left.toFloat(), draw.bounds.top.toFloat(), 0f)
                val scale = minOf(draw.bounds.width, draw.bounds.height) / 16f
                graphics.pose().scale(scale, scale, 1f)
                graphics.renderItem(stack, 0, 0)
                graphics.pose().popPose()
                return
            }

            val sprite = IdeToolbarIconAtlas.sprite(draw)
            graphics.setColor(draw.color.red(), draw.color.green(), draw.color.blue(), draw.color.alpha())
            graphics.blit(
                IDE_TOOLBAR_ICON_TEXTURE,
                sprite.bounds.left,
                sprite.bounds.top,
                sprite.bounds.width,
                sprite.bounds.height,
                sprite.sourceX.toFloat(),
                0f,
                IdeToolbarIconAtlas.CELL_SIZE,
                IdeToolbarIconAtlas.CELL_SIZE,
                IdeToolbarIconAtlas.WIDTH,
                IdeToolbarIconAtlas.HEIGHT,
            )
            graphics.setColor(1f, 1f, 1f, 1f)
        }

        override fun text(draw: IdeTextDraw) {
            draw.clip?.let { enableScissor(graphics, viewport, it.left, it.top, it.right, it.bottom) }
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
                    JetBrainsMonoRendering.drawString(graphics, font, value, glyph.x, textY, draw.color, flush = false)
                }
                graphics.flush()
            }
            if (draw.clip != null) graphics.disableScissor()
        }

        override fun terminal(
            state: ru.lazyhat.compukters.lang.runtime.vm.TerminalState,
            geometry: TerminalGridGeometry,
            nowMillis: Long,
        ) {
            TerminalGridRenderer.draw(graphics, font, state, geometry, viewport, nowMillis)
        }
    }

    private fun enableScissor(
        graphics: GuiGraphics,
        viewport: CompuktersUiViewport,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        graphics.enableScissor(
            floor(viewport.toMinecraftX(left.toDouble())).toInt(),
            floor(viewport.toMinecraftY(top.toDouble())).toInt(),
            ceil(viewport.toMinecraftX(right.toDouble())).toInt(),
            ceil(viewport.toMinecraftY(bottom.toDouble())).toInt(),
        )
    }
}

private const val CLOCKWISE_QUARTER_TURN_DEGREES = 90f

private fun Int.red(): Float = (this ushr 16 and 0xFF) / 255f

private fun Int.green(): Float = (this ushr 8 and 0xFF) / 255f

private fun Int.blue(): Float = (this and 0xFF) / 255f

private fun Int.alpha(): Float = (this ushr 24 and 0xFF) / 255f
