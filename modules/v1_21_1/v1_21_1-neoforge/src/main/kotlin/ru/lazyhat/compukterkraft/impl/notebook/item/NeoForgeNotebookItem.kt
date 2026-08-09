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

package ru.lazyhat.compukterkraft.impl.notebook.item

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraft.world.item.Item
import ru.lazyhat.compukterkraft.common.notebook.block.NotebookBlock
import ru.lazyhat.compukterkraft.common.notebook.item.NotebookItem
import ru.lazyhat.compukterkraft.impl.notebook.render.NotebookItemRenderer
import software.bernie.geckolib.animatable.GeoItem
import software.bernie.geckolib.animatable.client.GeoRenderProvider
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.RawAnimation
import software.bernie.geckolib.util.GeckoLibUtil
import java.util.function.Consumer

class NeoForgeNotebookItem(
    block: NotebookBlock,
    properties: Item.Properties,
) : NotebookItem(block, properties),
    GeoItem {
    companion object {
        private val CLOSED: RawAnimation = RawAnimation.begin().thenLoop("closed")
    }

    private val animationCache: AnimatableInstanceCache = GeckoLibUtil.createInstanceCache(this)

    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        controllers.add(
            AnimationController(this, "notebook_item_lid", 0) { state ->
                state.setAndContinue(CLOSED)
            },
        )
    }

    override fun getAnimatableInstanceCache(): AnimatableInstanceCache = animationCache

    override fun createGeoRenderer(consumer: Consumer<GeoRenderProvider>) {
        consumer.accept(
            object : GeoRenderProvider {
                private val renderer: BlockEntityWithoutLevelRenderer by lazy { NotebookItemRenderer() }

                override fun getGeoItemRenderer(): BlockEntityWithoutLevelRenderer = renderer
            },
        )
    }
}
