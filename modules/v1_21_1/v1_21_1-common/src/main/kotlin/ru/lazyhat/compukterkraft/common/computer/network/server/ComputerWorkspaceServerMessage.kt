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

package ru.lazyhat.compukterkraft.common.computer.network.server
import ru.lazyhat.compukterkraft.common.network.ServerNetworking
import ru.lazyhat.compukterkraft.common.network.ServerNetworkContext

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.inventory.AbstractContainerMenu
import ru.lazyhat.compukterkraft.common.computer.context.ServerContext
import ru.lazyhat.compukterkraft.common.computer.menu.ComputerMenu
import ru.lazyhat.compukterkraft.common.network.MessageType
import ru.lazyhat.compukterkraft.common.network.NetworkMessages
import ru.lazyhat.compukterkraft.common.computer.network.client.ComputerWorkspaceClientMessage

class ComputerWorkspaceServerMessage : ComputerServerMessage {
    private val action: Action
    private val path: String
    private val text: String

    constructor(
        menu: AbstractContainerMenu,
        action: Action,
        path: String = "",
        text: String = "",
    ) : super(menu) {
        this.action = action
        this.path = path
        this.text = text
    }

    constructor(buf: FriendlyByteBuf) : super(buf) {
        action = buf.readEnum(Action::class.java)
        path = buf.readUtf()
        text = buf.readUtf(Short.MAX_VALUE.toInt())
    }

    override fun write(buf: FriendlyByteBuf) {
        super.write(buf)
        buf.writeEnum(action)
        buf.writeUtf(path)
        buf.writeUtf(text, Short.MAX_VALUE.toInt())
    }

    override fun handle(
        context: ServerNetworkContext,
        container: ComputerMenu,
    ) {
        val computer = container.serverSide.computer
        val workspace = ServerContext.computerManager.workspace
        val player = context.sender()
        when (action) {
            Action.LIST -> {
                val entries = workspace.list(computer.instanceID, path)
                ServerNetworking.sendToPlayer(
                    ComputerWorkspaceClientMessage(targetContainerId, entries),
                    player,
                )
            }

            Action.READ -> {
                val document = workspace.readDocument(computer.instanceID, path)
                ServerNetworking.sendToPlayer(
                    ComputerWorkspaceClientMessage(targetContainerId, document),
                    player,
                )
            }

            Action.WRITE -> {
                val document = workspace.writeDocument(computer.instanceID, path, text)
                ServerNetworking.sendToPlayer(
                    ComputerWorkspaceClientMessage(targetContainerId, document),
                    player,
                )
            }
        }
    }

    override fun type(): MessageType<ComputerWorkspaceServerMessage> = NetworkMessages.COMPUTER_WORKSPACE_REQUEST

    enum class Action {
        LIST,
        READ,
        WRITE,
    }
}
