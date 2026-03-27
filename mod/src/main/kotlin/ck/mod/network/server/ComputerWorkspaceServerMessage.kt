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

package ck.mod.network.server

import ck.mod.menu.ComputerMenu
import ck.mod.network.MessageType
import ck.mod.network.NetworkMessages
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.inventory.AbstractContainerMenu

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
        val computer = container.getComputerPublic()
        val supervisor = ck.mod.context.ServerContext.vmSupervisor
        val player = context.sender()
        when (action) {
            Action.LIST -> {
                val entries = supervisor.workspace.list(computer.instanceID, path)
                ServerNetworking.sendToPlayer(
                    ck.mod.network.client
                        .ComputerWorkspaceClientMessage(targetContainerId, entries),
                    player,
                )
            }

            Action.READ -> {
                val document = supervisor.workspace.readDocument(computer.instanceID, path)
                ServerNetworking.sendToPlayer(
                    ck.mod.network.client
                        .ComputerWorkspaceClientMessage(targetContainerId, document),
                    player,
                )
            }

            Action.WRITE -> {
                val document = supervisor.workspace.writeDocument(computer.instanceID, path, text)
                ServerNetworking.sendToPlayer(
                    ck.mod.network.client
                        .ComputerWorkspaceClientMessage(targetContainerId, document),
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
