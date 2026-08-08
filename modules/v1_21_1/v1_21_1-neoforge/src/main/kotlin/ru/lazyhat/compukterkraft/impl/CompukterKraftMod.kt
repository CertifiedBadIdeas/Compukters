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

package ru.lazyhat.compukterkraft.impl

import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.SimpleMenuProvider
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent
import ru.lazyhat.compukterkraft.common.binding.ModObjects
import ru.lazyhat.compukterkraft.common.computer.data.ComputerContainerData
import ru.lazyhat.compukterkraft.common.computer.menu.ComputerControlMenu
import ru.lazyhat.compukterkraft.common.network.ClientNetworking
import ru.lazyhat.compukterkraft.common.network.ServerNetworking
import ru.lazyhat.compukterkraft.common.notebook.block.NotebookBlockEntity
import ru.lazyhat.compukterkraft.core.LOGGER
import ru.lazyhat.compukterkraft.core.MOD_ID
import ru.lazyhat.compukterkraft.core.MOD_NAME
import ru.lazyhat.compukterkraft.impl.platform.NetworkHandler
import ru.lazyhat.compukterkraft.lang.runtime.kraftos.KraftOsArtifactManifest

@Mod(MOD_ID)
class CompukterKraftMod(
    modEventBus: IEventBus,
    dist: Dist,
) {
    init {
        LOGGER.debug { "$MOD_ID has started!" }

        ModRegistry.register(modEventBus)
        val kraftOsArtifactManifest = lazy(KraftOsArtifactManifest::load)
        ModObjects.sdkArtifactIdentityComponentType = { ModRegistry.DataComponents.SDK_ARTIFACT_IDENTITY.get() }
        ModObjects.isKnownSdkArtifactIdentity = { identity ->
            identity in kraftOsArtifactManifest.value.sdkArtifacts
        }
        ModObjects.notebookBlockEntityType = { ModRegistry.BlockEntities.NOTEBOOK.get() }
        ModObjects.computerMenuType = { ModRegistry.Menus.COMPUTER.get() }
        ModObjects.openComputerMenu = { player: ServerPlayer, computer, menuData: ComputerContainerData ->
            player.openMenu(
                SimpleMenuProvider(
                    computer,
                    computer.name,
                ),
            ) { buffer ->
                menuData.toBytes(buffer)
            }
        }
        ModObjects.computerControlMenuType = { ModRegistry.Menus.COMPUTER_CONTROL.get() }
        ModObjects.openComputerControlMenu = { player: ServerPlayer, computer, menuData: ComputerContainerData ->
            player.openMenu(
                SimpleMenuProvider(
                    { id, playerInventory, _ ->
                        val notebook = computer as? NotebookBlockEntity
                        ComputerControlMenu(
                            ModRegistry.Menus.COMPUTER_CONTROL.get(),
                            id,
                            playerInventory,
                            computer.getOrCreateRuntimeDevice(),
                            onRemoved = { notebook?.notebookMenuClosed() },
                        ).also {
                            notebook?.notebookMenuOpened()
                        }
                    },
                    Component.translatable("gui.compukterkraft.computer_control.title"),
                ),
            ) { buffer ->
                menuData.toBytes(buffer)
            }
        }
        ModObjects.blockNamedEntityLootConditionType = { ModRegistry.LootItemConditionTypes.BLOCK_NAMED.get() }
        ModObjects.hasComputerIdLootConditionType = { ModRegistry.LootItemConditionTypes.HAS_ID.get() }
        ModObjects.playerCreativeLootConditionType = { ModRegistry.LootItemConditionTypes.PLAYER_CREATIVE.get() }

        NetworkHandler.setup(modEventBus)
        ServerNetworking.playerSender = NetworkHandler::sendToPlayer
        ClientNetworking.serverSender = NetworkHandler::sendToServer

        if (dist != Dist.CLIENT) {
            modEventBus.addListener(::onServerSetup)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onServerSetup(event: FMLDedicatedServerSetupEvent) {
        LOGGER.info { "Initializing server... with $MOD_NAME!" }
    }
}
