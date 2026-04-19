package ru.lazyhat.compukterkraft.common.ui.program

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import ru.lazyhat.compukterkraft.core.ui.program.RenderBackend

class GuiGraphicsRenderBackend(
    private val graphics: GuiGraphics,
    private val font: Font,
) : RenderBackend {
    override fun fillRect(x: Int, y: Int, width: Int, height: Int, color: Int) {
        graphics.fill(x, y, x + width, y + height, color)
    }

    override fun drawText(x: Int, y: Int, text: String, color: Int) {
        graphics.drawString(font, text, x, y, color, false)
    }

    override fun drawTerminalSurface(x: Int, y: Int, snapshot: Any?) {
        graphics.fill(x - 1, y - 1, x + 1, y + 1, 0xFF222938.toInt())
        TerminalSurfaceBridge.draw(graphics, x, y, snapshot)
    }
}
