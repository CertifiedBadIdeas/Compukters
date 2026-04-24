package ru.lazyhat.compukterkraft.common.ui.program

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import ru.lazyhat.compukterkraft.common.computer.menu.AbstractComputerMenu
import ru.lazyhat.compukterkraft.common.computer.screen.ComputerScreen
import ru.lazyhat.compukterkraft.core.platform.api.FontMetrics
import ru.lazyhat.compukterkraft.core.ui.foundation.UiElement
import ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompiler
import ru.lazyhat.compukterkraft.core.ui.program.ScreenRuntimeExecutor

/**
 * Base class for Minecraft container screens whose content is described with
 * the UI DSL.
 *
 * The content tree is compiled once inside Minecraft's [init] (after `width`,
 * `height`, `leftPos`, `topPos` are populated) and re-compiled on every
 * subsequent `init` call (window resizes). Per-frame [renderBg] only walks the
 * pre-baked render ops — no compilation, no layout, no map lookups.
 */
abstract class DslContainerScreen<T : AbstractComputerMenu>(
    menu: T,
    inventory: Inventory,
    title: Component,
) : ComputerScreen<T>(menu, inventory, title) {
    private var executor: ScreenRuntimeExecutor? = null

    abstract fun content(): UiElement

    override fun init() {
        super.init()
        val program =
            ScreenProgramCompiler(fontMetrics = FontMetrics { text -> font.width(text) })
                .compile(
                    root = content(),
                    rootX = leftPos,
                    rootY = topPos,
                    rootWidth = imageWidth,
                    rootHeight = imageHeight,
                )
        executor = ScreenRuntimeExecutor(program)
    }

    override fun renderLabels(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
    ) {
    }

    override fun renderBg(
        guiGraphics: GuiGraphics,
        partialTick: Float,
        mouseX: Int,
        mouseY: Int,
    ) {
        executor?.render(GuiGraphicsRenderBackend(guiGraphics, font))
    }

    override fun mouseClicked(
        x: Double,
        y: Double,
        button: Int,
    ): Boolean {
        val executor = executor
        return (executor != null && executor.mouseClicked(x.toInt(), y.toInt())) ||
            super.mouseClicked(x, y, button)
    }

    override fun keyPressed(
        keyCode: Int,
        scanCode: Int,
        modifiers: Int,
    ): Boolean {
        val executor = executor
        return (executor != null && executor.keyPressed(keyCode)) ||
            super.keyPressed(keyCode, scanCode, modifiers)
    }
}
