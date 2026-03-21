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

package ru.lazyhat.compukterkraft.computer

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
import ru.lazyhat.compukterkraft.Config
import ru.lazyhat.compukterkraft.LOGGER
import ru.lazyhat.compukterkraft.MOD_ID
import ru.lazyhat.compukterkraft.block.ComputerFamily
import ru.lazyhat.compukterkraft.context.ServerContext
import ru.lazyhat.compukterkraft.gui.NetworkedTerminal

class ServerComputer(
    val instanceID: Int,
    val level: ServerLevel,
    val blockPos: BlockPos,
    properties: ComputerProperties,
) : ComputerEvents.Receiver {
    val family = properties.family
    val terminal =
        NetworkedTerminal(
            Config.DEFAULT_COMPUTER_TERM_WIDTH,
            Config.DEFAULT_COMPUTER_TERM_HEIGHT,
            family != ComputerFamily.NORMAL,
        )

    init {
        LOGGER.info { "ComputerID: $instanceID init" }
    }

    var isOn = false
        private set

    fun checkUsable(player: Player) = true

    override fun queueEvent(
        event: String,
        arguments: Array<Any>,
    ) {
        LOGGER.info { "ComputerID: $instanceID event $event" }
    }

    fun shutdown() {
        LOGGER.info { "ComputerID: $instanceID shutdown" }
        isOn = false
    }

    fun turnOn() {
        LOGGER.info { "ComputerID: $instanceID turnOn" }
        isOn = true
        val biosStream =
            ServerContext
                .server
                .resourceManager
                .getResource(
                    ResourceLocation
                        .fromNamespaceAndPath(MOD_ID, "kotlin/bios.cc.kts"),
                ).orElse(null)!!
                .open()
//        BasicJvmScriptingHost().eval(
//            biosStream.readAllBytes().decodeToString().toScriptSource(),
//            ComputerScriptCompilationConfiguration(),
//            null,
//        )
    }

    fun reboot() {
        LOGGER.info { "ComputerID: $instanceID reboot" }
    }

    fun close() {
        LOGGER.info { "ComputerID: $instanceID close" }
        isOn = false
    }
}
