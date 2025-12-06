// SPDX-FileCopyrightText: 2018 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.network.client

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
