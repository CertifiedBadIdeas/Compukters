/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package ck.mod.gui

import net.minecraft.client.renderer.RenderType

/**
 * Shared [RenderType]s used throughout the mod.
 */
object RenderTypes {
    const val FULL_BRIGHT_LIGHTMAP: Int = (0xF shl 4) or (0xF shl 20)

    // private var monitorTboShader: MonitorTextureBufferShader? = null

    /**
     * Renders a fullbright terminal.
     */
    val TERMINAL: RenderType = RenderType.text(FixedWidthFontRenderer.FONT)

//
//    /**
//     * Renders a monitor with the TBO shader.
//     *
//     * @see MonitorTextureBufferShader
//     */
//    val MONITOR_TBO: RenderType = Types.Companion.MONITOR_TBO
//
//    /**
//     * A variant of [.TERMINAL] which uses the lightmap rather than rendering fullbright.
//     */
//    val PRINTOUT_TEXT: RenderType = RenderType.text(FixedWidthFontRenderer.FONT)
//
//    /**
//     * Printout's background texture. [RenderType.text] is a *little* questionable, but
//     * it is what maps use, so should behave the same as vanilla in both item frames and in-hand.
//     */
//    val PRINTOUT_BACKGROUND: RenderType =
//        RenderType.text(
//            ResourceLocation.fromNamespaceAndPath(MOD_ID, "textures/gui/printout.png"),
//        )
//

    /**
     * Render type for [GUI sprites][GuiSprites].
     */
    val GUI_SPRITES: RenderType = RenderType.text(GuiSprites.TEXTURE)
//
//    val monitorTextureBufferShader: MonitorTextureBufferShader
//        get() {
//            if (monitorTboShader == null) throw NullPointerException("MonitorTboShader has not been registered")
//            return monitorTboShader
//        }
//
//    val terminalShader: ShaderInstance
//        get() =
//            Objects.requireNonNull<ShaderInstance>(
//                GameRenderer.getRendertypeTextShader(),
//                "Text shader has not been registered",
//            )
//
//    @Throws(IOException::class)
//    fun registerShaders(
//        resources: ResourceProvider?,
//        load: ShaderLoader,
//    ) {
//        load.tryLoad(
//            "monitor shader",
//            IOSupplier {
//                MonitorTextureBufferShader(
//                    resources,
//                    ComputerCraftAPI.MOD_ID + "/monitor_tbo",
//                    MONITOR_TBO.format(),
//                )
//            },
//            Consumer { x: ShaderInstance? -> monitorTboShader = x as MonitorTextureBufferShader },
//        )
//    }
//
//    interface ShaderLoader {
//        @Throws(IOException::class)
//        fun tryLoad(
//            name: String?,
//            create: IOSupplier<ShaderInstance?>?,
//            accept: Consumer<ShaderInstance?>?,
//        )
//    }
//
//    private class Types(
//        name: String,
//        format: VertexFormat,
//        mode: VertexFormat.Mode,
//        buffer: Int,
//        crumbling: Boolean,
//        sort: Boolean,
//        setup: Runnable,
//        teardown: Runnable,
//    ) : RenderType(name, format, mode, buffer, crumbling, sort, setup, teardown) {
//        companion object {
//            private val TERM_FONT_TEXTURE =
//                TextureStateShard(
//                    FixedWidthFontRenderer.FONT,
//                    false,
//                    false, // blur, minimap
//                )
//
//            val MONITOR_TBO: RenderType =
//                create(
//                    "monitor_tbo",
//                    DefaultVertexFormat.POSITION_TEX,
//                    VertexFormat.Mode.TRIANGLE_STRIP,
//                    128,
//                    false,
//                    false, // useDelegate, needsSorting
//                    CompositeState
//                        .builder()
//                        .setTextureState(TERM_FONT_TEXTURE)
//                        .setShaderState(ShaderStateShard(Supplier { obj: RenderTypes? -> monitorTextureBufferShader }))
//                        .createCompositeState(false),
//                )
//        }
//    }
}
