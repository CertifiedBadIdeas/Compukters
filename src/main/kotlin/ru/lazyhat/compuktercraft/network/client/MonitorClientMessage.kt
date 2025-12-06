// SPDX-FileCopyrightText: 2020 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.network.client

// class MonitorClientMessage : NetworkMessage<ClientNetworkContext?> {
// 	private val pos: BlockPos
//
// 	@Nullable
// 	private val state: TerminalState?
//
// 	constructor(
// 		pos: BlockPos,
// 		@Nullable
// 		state: TerminalState?
// 	) {
// 		this.pos = pos
// 		this.state = state
// 	}
//
// 	constructor(buf: FriendlyByteBuf) {
// 		pos = buf.readBlockPos()
// 		state = buf.readNullable<TerminalState?>(FriendlyByteBuf.Reader { TerminalState() })
// 	}
//
// 	public override fun write(buf: FriendlyByteBuf) {
// 		buf.writeBlockPos(pos)
// 		buf.writeNullable<Any?>(state, FriendlyByteBuf.Writer { b: FriendlyByteBuf?, t: Any? -> t.write(b) })
// 	}
//
// 	public override fun handle(context: ClientNetworkContext) {
// 		context.handleMonitorData(pos, state)
// 	}
//
// 	public override fun type(): MessageType<MonitorClientMessage?> {
// 		return NetworkMessages.MONITOR_CLIENT
// 	}
// }
