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

package ru.lazyhat.compukterkraft.impl.notebook.render

import net.minecraft.client.multiplayer.ClientLevel
import ru.lazyhat.compukterkraft.common.computer.client.retained.ClientRetainedDisplays
import ru.lazyhat.compukterkraft.common.computer.client.retained.MinecraftRetainedNativePresentation
import ru.lazyhat.compukterkraft.common.computer.client.retained.RetainedDisplayObserverHandle
import ru.lazyhat.compukterkraft.impl.notebook.block.NeoForgeNotebookBlockEntity
import java.util.IdentityHashMap

object NotebookRetainedDisplayObservers : AutoCloseable {
    private val observations = IdentityHashMap<NeoForgeNotebookBlockEntity, Observation>()
    private var clientTick = 0L

    fun presentation(notebook: NeoForgeNotebookBlockEntity): MinecraftRetainedNativePresentation? {
        val level = notebook.level as? ClientLevel ?: return null
        if (notebook.isRemoved) return null
        val computerId = notebook.computerID ?: return null
        require(computerId > 0) { "Notebook retained display contains invalid computer ID: $computerId" }

        var observation = observations[notebook]
        if (observation == null || observation.computerId != computerId) {
            observations.remove(notebook)
            observation?.handle?.close()
            observation =
                Observation(
                    computerId,
                    ClientRetainedDisplays.attachNotebook(computerId, level.dimension(), notebook.blockPos),
                    clientTick,
                )
            observations[notebook] = observation
        }
        observation.lastRenderedTick = clientTick
        return observation.handle.presentation()
    }

    fun tick(activeLevel: ClientLevel?) {
        clientTick += 1
        val expired =
            observations.entries
                .filter { (notebook, observation) ->
                    notebook.level !== activeLevel ||
                        notebook.isRemoved ||
                        clientTick - observation.lastRenderedTick > INVISIBLE_GRACE_TICKS
                }.map { it.key to it.value }
        expired.forEach { (notebook) -> observations.remove(notebook) }
        closeAll(expired.map { it.second })
    }

    override fun close() {
        val closing = observations.values.toList()
        observations.clear()
        closeAll(closing)
    }

    private fun closeAll(closing: Iterable<Observation>) {
        var failure: Throwable? = null
        for (observation in closing) {
            try {
                observation.handle.close()
            } catch (caught: Throwable) {
                if (failure == null) {
                    failure = caught
                } else if (failure !== caught) {
                    failure.addSuppressed(caught)
                }
            }
        }
        failure?.let { throw it }
    }

    private data class Observation(
        val computerId: Int,
        val handle: RetainedDisplayObserverHandle,
        var lastRenderedTick: Long,
    )

    private const val INVISIBLE_GRACE_TICKS = 20L
}
