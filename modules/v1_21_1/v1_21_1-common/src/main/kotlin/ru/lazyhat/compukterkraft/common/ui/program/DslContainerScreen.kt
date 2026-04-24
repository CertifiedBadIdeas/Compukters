package ru.lazyhat.compukterkraft.common.ui.program

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import ru.lazyhat.compukterkraft.common.computer.menu.AbstractComputerMenu
import ru.lazyhat.compukterkraft.common.computer.screen.ComputerScreen
import ru.lazyhat.compukterkraft.core.platform.api.FontMetrics
import ru.lazyhat.compukterkraft.core.ui.foundation.UiElement
import ru.lazyhat.compukterkraft.core.ui.program.CompiledScreen
import ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompiler
import ru.lazyhat.compukterkraft.core.ui.program.ScreenRuntimeExecutor

abstract class DslContainerScreen<T : AbstractComputerMenu>(
    menu: T,
    inventory: Inventory,
    title: Component,
) : ComputerScreen<T>(menu, inventory, title) {
    private val compiler by lazy {
        ScreenProgramCompiler(fontMetrics = FontMetrics { text -> font.width(text) })
    }

    private var executor: ScreenRuntimeExecutor? = null

    abstract fun content(): UiElement

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
        val rebuilt = rebuildExecutor()
        rebuilt.render(GuiGraphicsRenderBackend(guiGraphics, font))
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

    private fun rebuildExecutor(): ScreenRuntimeExecutor {
        val compiled: CompiledScreen = compiler.compile(content())
        return ScreenRuntimeExecutor(compiled).also { executor = it }
    }
}
