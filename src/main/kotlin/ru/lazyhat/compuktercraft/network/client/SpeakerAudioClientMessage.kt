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
// class SpeakerAudioClientMessage : NetworkMessage<ClientNetworkContext?> {
// 	private val source: UUID
// 	private val pos: SpeakerPosition.Message
// 	private val content: EncodedAudio
// 	private val volume: Float
//
// 	constructor(source: UUID, pos: SpeakerPosition, volume: Float, content: EncodedAudio) {
// 		this.source = source
// 		this.pos = pos.asMessage()
// 		this.content = content
// 		this.volume = volume
// 	}
//
// 	constructor(buf: FriendlyByteBuf) {
// 		source = buf.readUUID()
// 		pos = SpeakerPosition.Message.read(buf)
// 		volume = buf.readFloat()
// 		content = EncodedAudio.read(buf)
// 	}
//
// 	public override fun write(buf: FriendlyByteBuf) {
// 		buf.writeUUID(source)
// 		pos.write(buf)
// 		buf.writeFloat(volume)
// 		content.write(buf)
// 	}
//
// 	public override fun handle(context: ClientNetworkContext) {
// 		context.handleSpeakerAudio(source, pos, volume, content)
// 	}
//
// 	public override fun type(): MessageType<SpeakerAudioClientMessage?> {
// 		return NetworkMessages.SPEAKER_AUDIO
// 	}
// }
