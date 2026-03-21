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
package ru.lazyhat.compukterkraft.network.client

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
