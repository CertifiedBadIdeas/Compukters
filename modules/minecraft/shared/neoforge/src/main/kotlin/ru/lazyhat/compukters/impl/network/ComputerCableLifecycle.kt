/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.impl.network

import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.event.level.ChunkEvent
import ru.lazyhat.compukters.minecraft.network.ComputerCableLinks
import ru.lazyhat.compukters.minecraft.network.ComputerCableMessages

object ComputerCableLifecycle {
    fun onChunkLoad(event: ChunkEvent.Load) = invalidate(event.level as? ServerLevel, event.chunk.pos)

    fun onChunkUnload(event: ChunkEvent.Unload) = invalidate(event.level as? ServerLevel, event.chunk.pos)

    fun onLevelUnload(event: net.neoforged.neoforge.event.level.LevelEvent.Unload) {
        val level = event.level as? ServerLevel ?: return
        ComputerCableMessages.clear(level)
        ComputerCableLinks.invalidate(level)
    }

    private fun invalidate(
        level: ServerLevel?,
        chunk: net.minecraft.world.level.ChunkPos,
    ) {
        if (level == null) return
        val action = {
            ComputerCableLinks.invalidate(level)
            ComputerCableMessages.chunkChanged(level, chunk)
        }
        if (level.server.isSameThread) action() else level.server.execute(action)
    }
}
