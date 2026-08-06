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

package ru.lazyhat.compukterkraft.common.computer.client.retained

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import ru.lazyhat.compukterkraft.common.computer.network.retained.RetainedDisplayAttachServerMessage
import ru.lazyhat.compukterkraft.common.computer.network.retained.RetainedDisplayBinding
import ru.lazyhat.compukterkraft.common.computer.network.retained.RetainedDisplayControlServerMessage
import ru.lazyhat.compukterkraft.common.computer.network.retained.RetainedDisplayDetachServerMessage
import ru.lazyhat.compukterkraft.common.network.ClientNetworking

data class RetainedDisplayMenuObserverIdentity(
    val containerId: Int,
)

data class RetainedDisplayNotebookObserverIdentity(
    val dimension: ResourceKey<Level>,
    val blockPos: BlockPos,
)

object ClientRetainedDisplays {
    private var registry = createRegistry()

    fun attachMenu(
        computerId: Int,
        containerId: Int,
    ): RetainedDisplayObserverHandle =
        registry.attach(
            requireComputerId(computerId),
            RetainedDisplayObserverKey(
                RetainedDisplayMenuObserverIdentity(containerId),
                RetainedDisplayViewKind.MENU,
            ),
        )

    fun attachNotebook(
        computerId: Int,
        dimension: ResourceKey<Level>,
        blockPos: BlockPos,
    ): RetainedDisplayObserverHandle =
        registry.attach(
            requireComputerId(computerId),
            RetainedDisplayObserverKey(
                RetainedDisplayNotebookObserverIdentity(dimension, blockPos.immutable()),
                RetainedDisplayViewKind.WORLD,
            ),
        )

    fun apply(
        computerId: Int,
        payload: ByteArray,
    ) {
        val id = requireComputerId(computerId)
        val entry = registry.entry(id) ?: return
        when (val result = entry.apply(payload)) {
            is RetainedDisplayClientInstallResult.Installed -> sendControl(computerId, result.acknowledgement)
            is RetainedDisplayClientInstallResult.ResyncRequired -> result.request?.let { sendControl(computerId, it) }
        }
    }

    fun invalidateAllRenderResources() {
        cleanupAll(registry.entriesSnapshot()) { (computerId, entry) ->
            entry.invalidateRenderResources().request?.let { sendControl(computerId.toInt(), it) }
        }
    }

    fun close() {
        val closing = registry
        registry = createRegistry()
        closing.discardConnection()
    }

    private fun createRegistry(): RetainedDisplayClientRegistry =
        RetainedDisplayClientRegistry(
            nativeCacheFactory = { computerId ->
                MinecraftRetainedDisplayCache.create(Minecraft.getInstance().textureManager, computerId)
            },
            onFirstObserver = { computerId, observer ->
                val binding =
                    when (val identity = observer.identity) {
                        is RetainedDisplayMenuObserverIdentity -> {
                            RetainedDisplayBinding.Menu(identity.containerId)
                        }

                        is RetainedDisplayNotebookObserverIdentity -> {
                            RetainedDisplayBinding.Notebook(identity.dimension, identity.blockPos)
                        }

                        else -> {
                            error("Unknown retained display observer identity: $identity")
                        }
                    }
                ClientNetworking.sendToServer(RetainedDisplayAttachServerMessage(computerId.toInt(), binding))
            },
            onLastObserver = { computerId ->
                ClientNetworking.sendToServer(RetainedDisplayDetachServerMessage(computerId.toInt()))
            },
        )

    private fun sendControl(
        computerId: Int,
        payload: ByteArray,
    ) {
        ClientNetworking.sendToServer(RetainedDisplayControlServerMessage(computerId, payload))
    }

    private fun requireComputerId(computerId: Int): UInt {
        require(computerId > 0) { "Retained display computer ID must be positive" }
        return computerId.toUInt()
    }
}
