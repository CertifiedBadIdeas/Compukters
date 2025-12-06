// SPDX-FileCopyrightText: 2022 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.network.client

/**
 * Provides additional data about a client computer, such as its ID and current state.
 */
// class PocketComputerDataMessage : NetworkMessage<ClientNetworkContext?> {
// 	private val clientId: UUID
// 	private val state: ComputerState
// 	private val lightState: Int
//
// 	@Nullable
// 	private val terminal: TerminalState?
//
// 	constructor(computer: PocketServerComputer, sendTerminal: Boolean) {
// 		clientId = computer.getInstanceUUID()
// 		state = computer.getState()
// 		lightState = computer.getBrain().getLight()
// 		terminal = if (sendTerminal) computer.getTerminalState() else null
// 	}
//
// 	constructor(buf: FriendlyByteBuf) {
// 		clientId = buf.readUUID()
// 		state = buf.readEnum<T>(ComputerState::class.java)
// 		lightState = buf.readVarInt()
// 		terminal = buf.readNullable<TerminalState?>(FriendlyByteBuf.Reader { TerminalState() })
// 	}
//
// 	public override fun write(buf: FriendlyByteBuf) {
// 		buf.writeUUID(clientId)
// 		buf.writeEnum(state)
// 		buf.writeVarInt(lightState)
// 		buf.writeNullable<Any?>(terminal, FriendlyByteBuf.Writer { b: FriendlyByteBuf?, t: Any? -> t.write(b) })
// 	}
//
// 	public override fun handle(context: ClientNetworkContext) {
// 		context.handlePocketComputerData(clientId, state, lightState, terminal)
// 	}
//
// 	public override fun type(): MessageType<PocketComputerDataMessage?> {
// 		return NetworkMessages.POCKET_COMPUTER_DATA
// 	}
// }
