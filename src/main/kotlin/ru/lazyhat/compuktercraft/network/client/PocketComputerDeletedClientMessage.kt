// SPDX-FileCopyrightText: 2018 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.network.client

// class PocketComputerDeletedClientMessage : NetworkMessage<ClientNetworkContext?> {
// 	private val instanceId: UUID
//
// 	constructor(instanceId: UUID) {
// 		this.instanceId = instanceId
// 	}
//
// 	constructor(buffer: FriendlyByteBuf) {
// 		instanceId = buffer.readUUID()
// 	}
//
// 	public override fun write(buf: FriendlyByteBuf) {
// 		buf.writeUUID(instanceId)
// 	}
//
// 	public override fun handle(context: ClientNetworkContext) {
// 		context.handlePocketComputerDeleted(instanceId)
// 	}
//
// 	public override fun type(): MessageType<PocketComputerDeletedClientMessage?> {
// 		return NetworkMessages.POCKET_COMPUTER_DELETED
// 	}
// }
