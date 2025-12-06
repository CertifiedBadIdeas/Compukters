// SPDX-FileCopyrightText: 2021 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.network.client

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
