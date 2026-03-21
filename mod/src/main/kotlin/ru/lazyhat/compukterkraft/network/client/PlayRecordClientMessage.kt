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
 * Starts or stops a record on the client, depending on if [.soundEvent] is `null`.
 *
 *
 * Used by disk drives to play record items.
 *
 * @see DiskDriveBlockEntity
 */
// class PlayRecordClientMessage : NetworkMessage<ClientNetworkContext?> {
// 	private val pos: BlockPos
//
// 	@Nullable
// 	private val name: String?
//
// 	@Nullable
// 	private val soundEvent: SoundEvent?
//
// 	constructor(
// 		pos: BlockPos,
// 		event: SoundEvent?,
// 		@Nullable
// 		name: String?
// 	) {
// 		this.pos = pos
// 		this.name = name
// 		soundEvent = event
// 	}
//
// 	constructor(pos: BlockPos) {
// 		this.pos = pos
// 		name = null
// 		soundEvent = null
// 	}
//
// 	constructor(buf: FriendlyByteBuf) {
// 		pos = buf.readBlockPos()
// 		soundEvent = buf.readNullable<SoundEvent?>(FriendlyByteBuf.Reader { SoundEvent.readFromNetwork() })
// 		name = buf.readNullable<String?>(FriendlyByteBuf.Reader { obj: FriendlyByteBuf? -> obj.readUtf() })
// 	}
//
// 	public override fun write(buf: FriendlyByteBuf) {
// 		buf.writeBlockPos(pos)
// 		buf.writeNullable<SoundEvent?>(soundEvent, FriendlyByteBuf.Writer { b: FriendlyByteBuf?, e: SoundEvent? -> e!!.writeToNetwork(b) })
// 		buf.writeNullable<String?>(name, FriendlyByteBuf.Writer { obj: FriendlyByteBuf? -> obj.writeUtf() })
// 	}
//
// 	public override fun handle(context: ClientNetworkContext) {
// 		context.handlePlayRecord(pos, soundEvent, name)
// 	}
//
// 	public override fun type(): MessageType<PlayRecordClientMessage?> {
// 		return NetworkMessages.PLAY_RECORD
// 	}
// }
