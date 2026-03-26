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
 * Stops a sound on the client.
 *
 *
 * Called when a speaker is broken.
 *
 * @see SpeakerBlockEntity
 */
// class SpeakerStopClientMessage : NetworkMessage<ClientNetworkContext?> {
// 	private val source: UUID
//
// 	constructor(source: UUID) {
// 		this.source = source
// 	}
//
// 	constructor(buf: FriendlyByteBuf) {
// 		source = buf.readUUID()
// 	}
//
// 	public override fun write(buf: FriendlyByteBuf) {
// 		buf.writeUUID(source)
// 	}
//
// 	public override fun handle(context: ClientNetworkContext) {
// 		context.handleSpeakerStop(source)
// 	}
//
// 	public override fun type(): MessageType<SpeakerStopClientMessage?> {
// 		return NetworkMessages.SPEAKER_STOP
// 	}
// }
