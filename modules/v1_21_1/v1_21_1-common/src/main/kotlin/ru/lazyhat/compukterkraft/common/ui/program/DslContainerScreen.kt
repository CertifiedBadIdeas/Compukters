package ru.lazyhat.compukterkraft.common.ui.program

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import ru.lazyhat.compukterkraft.common.computer.menu.AbstractComputerMenu
import ru.lazyhat.compukterkraft.common.computer.screen.ComputerScreen
import ru.lazyhat.compukterkraft.core.ui.foundation.UiElement
import ru.lazyhat.compukterkraft.core.ui.program.ScreenProgram
import ru.lazyhat.compukterkraft.core.ui.program.ScreenProgramCompiler
import ru.lazyhat.compukterkraft.core.ui.program.ScreenRuntimeExecutor
import ru.lazyhat.compukterkraft.core.ui.program.SlotValues

abstract class DslContainerScreen<T : AbstractComputerMenu>(
    menu: T,
    inventory: Inventory,
    title: Component,
) : ComputerScreen<T>(menu, inventory, title) {
    private val compiler = ScreenProgramCompiler()
    private var currentExecutor: ScreenRuntimeExecutor? = null

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
        buildExecutor().let {
            currentExecutor = it
            it.render(
                GuiGraphicsRenderBackend(
                    guiGraphics,
                    font,
                ),
            )
        }
    }

    override fun keyPressed(
        key: Int,
        scancode: Int,
        modifiers: Int,
    ): Boolean {
        if (currentExecutor?.keyPressed(key) == true) {
            return true
        }

        return super.keyPressed(key, scancode, modifiers)
    }

    override fun mouseClicked(
        mouseX: Double,
        mouseY: Double,
        button: Int,
    ): Boolean {
        if (button == 0 &&
            currentExecutor?.mouseClicked(mouseX.toInt(), mouseY.toInt()) == true
        ) {
            return true
        }

        return super.mouseClicked(mouseX, mouseY, button)
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
