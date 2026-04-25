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
    private data class Clip(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    private val clipStack = ArrayDeque<Clip>()

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

    override fun pushClip(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        // Intersect with the current clip so nested ScrollAreas behave correctly.
        val parent = clipStack.lastOrNull()
        val clip =
            if (parent == null) {
                Clip(x, y, width, height)
            } else {
                val nx = maxOf(parent.x, x)
                val ny = maxOf(parent.y, y)
                val nx2 = minOf(parent.x + parent.width, x + width)
                val ny2 = minOf(parent.y + parent.height, y + height)
                Clip(nx, ny, (nx2 - nx).coerceAtLeast(0), (ny2 - ny).coerceAtLeast(0))
            }
        clipStack.addLast(clip)
        graphics.enableScissor(clip.x, clip.y, clip.x + clip.width, clip.y + clip.height)
    }

    override fun popClip() {
        if (clipStack.isEmpty()) return
        clipStack.removeLast()
        val parent = clipStack.lastOrNull()
        if (parent == null) {
            graphics.disableScissor()
        } else {
            graphics.enableScissor(parent.x, parent.y, parent.x + parent.width, parent.y + parent.height)
        }
    }
}
