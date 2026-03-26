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

// class UploadResultMessage : NetworkMessage<ClientNetworkContext> {
//    private val containerId: Int
//    private val result: UploadResult
//
//    private val errorMessage: Component?
//
//    private constructor(
//        container: AbstractContainerMenu,
//        result: UploadResult,
//        errorMessage: Component?,
//    ) {
//        containerId = container.containerId
//        this.result = result
//        this.errorMessage = errorMessage
//    }
//
//    constructor(buf: FriendlyByteBuf) {
//        containerId = buf.readVarInt()
//        result = buf.readEnum(UploadResult::class.java)
//        errorMessage = if (result === UploadResult.ERROR) buf.readComponent() else null
//    }
//
//    public override fun write(buf: FriendlyByteBuf) {
//        buf.writeVarInt(containerId)
//        buf.writeEnum(result)
//        if (result === UploadResult.ERROR) buf.writeComponent(checkNotNull(errorMessage))
//    }
//
//    public override fun handle(context: ClientNetworkContext) {
//        context.handleUploadResult(containerId, result, errorMessage)
//    }
//
//    public override fun type(): MessageType<UploadResultMessage> = NetworkMessages.UPLOAD_RESULT
//
//    companion object {
//        fun queued(container: AbstractContainerMenu): UploadResultMessage = UploadResultMessage(container, UploadResult.QUEUED, null)
//
//        fun consumed(container: AbstractContainerMenu): UploadResultMessage = UploadResultMessage(container, UploadResult.CONSUMED, null)
//
//        fun error(
//            container: AbstractContainerMenu,
//            errorMessage: Component?,
//        ): UploadResultMessage = UploadResultMessage(container, UploadResult.ERROR, errorMessage)
//    }
// }
