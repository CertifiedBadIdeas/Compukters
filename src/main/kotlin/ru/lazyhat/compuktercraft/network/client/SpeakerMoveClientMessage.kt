// SPDX-FileCopyrightText: 2021 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.network.client

/**
 * Starts a sound on the client.
 *
 *
 * Used by speakers to play sounds.
 *
 * @see SpeakerBlockEntity
 */
// class SpeakerMoveClientMessage : NetworkMessage<ClientNetworkContext?> {
// 	private val source: UUID
// 	private val pos: SpeakerPosition.Message
//
// 	constructor(source: UUID, pos: SpeakerPosition) {
// 		this.source = source
// 		this.pos = pos.asMessage()
// 	}
//
// 	constructor(buf: FriendlyByteBuf) {
// 		source = buf.readUUID()
// 		pos = SpeakerPosition.Message.read(buf)
// 	}
//
// 	public override fun write(buf: FriendlyByteBuf) {
// 		buf.writeUUID(source)
// 		pos.write(buf)
// 	}
//
// 	public override fun handle(context: ClientNetworkContext) {
// 		context.handleSpeakerMove(source, pos)
// 	}
//
// 	public override fun type(): MessageType<SpeakerMoveClientMessage?> {
// 		return NetworkMessages.SPEAKER_MOVE
// 	}
// }
