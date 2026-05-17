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

package ru.lazyhat.compukterkraft.impl.notebook.block

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import ru.lazyhat.compukterkraft.common.computer.block.ComputerBlockEntity
import ru.lazyhat.compukterkraft.common.notebook.block.NotebookBlockEntity
import software.bernie.geckolib.animatable.GeoBlockEntity
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.animation.AnimationController
import software.bernie.geckolib.animation.RawAnimation
import software.bernie.geckolib.util.GeckoLibUtil

class NeoForgeNotebookBlockEntity(
    type: BlockEntityType<out ComputerBlockEntity>,
    pos: BlockPos,
    state: BlockState,
) : NotebookBlockEntity(type, pos, state),
    GeoBlockEntity {
    companion object {
        private const val LID_CONTROLLER: String = "notebook_lid"
        private const val OPEN_TRIGGER: String = "open_lid"
        private const val CLOSE_TRIGGER: String = "close_lid"

        private val CLOSED: RawAnimation = RawAnimation.begin().thenLoop("closed")
        private val OPEN: RawAnimation = RawAnimation.begin().thenPlay("open").thenLoop("opened")
        private val CLOSE: RawAnimation = RawAnimation.begin().thenPlay("close").thenLoop("closed")
    }

    private val animationCache: AnimatableInstanceCache = GeckoLibUtil.createInstanceCache(this)

    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        controllers.add(
            AnimationController(this, LID_CONTROLLER, 0) { state ->
                state.setAndContinue(CLOSED)
            }.triggerableAnim(OPEN_TRIGGER, OPEN)
                .triggerableAnim(CLOSE_TRIGGER, CLOSE),
        )
    }

    override fun getAnimatableInstanceCache(): AnimatableInstanceCache = animationCache

    override fun setNotebookLidOpen(open: Boolean) {
        triggerAnim(LID_CONTROLLER, if (open) OPEN_TRIGGER else CLOSE_TRIGGER)
    }

    override fun onChunkUnloaded() {
        releaseRuntimeDevice()
        super.onChunkUnloaded()
    }
}
