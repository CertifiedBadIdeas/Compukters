package ru.lazyhat.compukterkraft.common.ui.program

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import ru.lazyhat.compukterkraft.core.ui.foundation.Color
import ru.lazyhat.compukterkraft.core.ui.program.RenderBackend
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

class GuiGraphicsRenderBackend(
    private val graphics: GuiGraphics,
    private val font: Font,
) : RenderBackend {
    override fun fillRect(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        color: Color,
    ) {
        graphics.fill(x, y, x + width, y + height, color.value.toInt())
    }

    override fun drawText(
        x: Int,
        y: Int,
        text: String,
        color: Color,
    ) {
        graphics.drawString(font, text, x, y, color.value.toInt(), false)
    }

    override fun drawTerminalSurface(
        x: Int,
        y: Int,
        snapshot: ScreenBufferSnapshot,
    ) {
        graphics.fill(x - 1, y - 1, x + 1, y + 1, 0xFF222938.toInt())
        TerminalSurfaceBridge.draw(graphics, x, y, snapshot)
    }
}
