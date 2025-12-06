// SPDX-FileCopyrightText: 2021 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.network.client

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
