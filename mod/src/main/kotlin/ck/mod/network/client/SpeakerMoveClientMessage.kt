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
package ck.mod.network.client

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
