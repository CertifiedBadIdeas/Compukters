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
package ck.mod.infrastructure.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import net.minecraft.client.Minecraft
import kotlin.coroutines.CoroutineContext

/**
 * A [CoroutineDispatcher] that dispatches coroutines onto the Minecraft client render thread
 * via [Minecraft.tell]. This guarantees all resumed continuations run on the same thread
 * that drives `Screen.render()`, `Screen.tick()`, and other client-side callbacks.
 *
 * Usage: `CoroutineScope(SupervisorJob() + Dispatchers.minecraft)`
 */
object MinecraftMainDispatcher : CoroutineDispatcher() {
    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        val minecraft = Minecraft.getInstance()
        if (minecraft.isSameThread) {
            block.run()
        } else {
            minecraft.tell(block)
        }
    }
}

/**
 * Dispatches coroutines onto the Minecraft client render thread.
 *
 * @see MinecraftMainDispatcher
 */
@Suppress("UnusedReceiverParameter")
val Dispatchers.minecraft: CoroutineDispatcher
    get() = MinecraftMainDispatcher
