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
import ru.lazyhat.compukterkraft.core.ui.program.SlotValues

abstract class DslContainerScreen<T : AbstractComputerMenu>(
    menu: T,
    inventory: Inventory,
    title: Component,
) : ComputerScreen<T>(menu, inventory, title) {
    private val compiler by lazy {
        ScreenProgramCompiler(fontMetrics = FontMetrics { text -> font.width(text) })
    }

    abstract fun content(): UiElement

    protected fun renderProgram(
        graphics: GuiGraphics,
        executor: ScreenRuntimeExecutor,
    ) {
        executor.render(GuiGraphicsRenderBackend(graphics, font))
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
        buildExecutor().render(
            GuiGraphicsRenderBackend(
                guiGraphics,
                font,
            ),
        )
    }

    private fun buildExecutor(): ScreenRuntimeExecutor =
        ScreenRuntimeExecutor(
            program = compiler.compile(content()),
            slotProvider = { SlotValues() },
            clickHandlers = emptyMap(),
            keyHandlers = emptyMap(),
            focusHandlers = emptyMap(),
        )
}
