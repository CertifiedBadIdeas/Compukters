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
// class SpeakerPlayClientMessage : NetworkMessage<ClientNetworkContext?> {
// 	private val source: UUID
// 	private val pos: SpeakerPosition.Message
// 	private val sound: ResourceLocation
// 	private val volume: Float
// 	private val pitch: Float
//
// 	constructor(source: UUID, pos: SpeakerPosition, sound: ResourceLocation, volume: Float, pitch: Float) {
// 		this.source = source
// 		this.pos = pos.asMessage()
// 		this.sound = sound
// 		this.volume = volume
// 		this.pitch = pitch
// 	}
//
// 	constructor(buf: FriendlyByteBuf) {
// 		source = buf.readUUID()
// 		pos = SpeakerPosition.Message.read(buf)
// 		sound = buf.readResourceLocation()
// 		volume = buf.readFloat()
// 		pitch = buf.readFloat()
// 	}
//
// 	public override fun write(buf: FriendlyByteBuf) {
// 		buf.writeUUID(source)
// 		pos.write(buf)
// 		buf.writeResourceLocation(sound)
// 		buf.writeFloat(volume)
// 		buf.writeFloat(pitch)
// 	}
//
// 	public override fun handle(context: ClientNetworkContext) {
// 		context.handleSpeakerPlay(source, pos, sound, volume, pitch)
// 	}
//
// 	public override fun type(): MessageType<SpeakerPlayClientMessage?> {
// 		return NetworkMessages.SPEAKER_PLAY
// 	}
// }
