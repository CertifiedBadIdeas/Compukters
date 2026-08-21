package ru.lazyhat.compukters.common.ui.program

import com.mojang.blaze3d.vertex.ByteBufferBuilder
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import ru.lazyhat.compukters.common.ui.render.FixedWidthFontRenderer
import ru.lazyhat.compukters.lang.runtime.ScreenBufferSnapshot

object TerminalSurfaceBridge {
    fun draw(
        graphics: GuiGraphics,
        x: Int,
        y: Int,
        snapshot: ScreenBufferSnapshot,
    ) {
        val renderType = RenderType.text(FixedWidthFontRenderer.FONT)
        val bufferSource = MultiBufferSource.immediate(ByteBufferBuilder(renderType.bufferSize()))
        val emitter = FixedWidthFontRenderer.toVertexConsumer(graphics.pose(), bufferSource.getBuffer(renderType))
        FixedWidthFontRenderer.drawTerminal(
            emitter,
            x.toFloat(),
            y.toFloat(),
            snapshot,
            0f,
            0f,
            0f,
            0f,
        )
        bufferSource.endBatch()
    }
}
