// SPDX-FileCopyrightText: 2018 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.network

import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.ints.IntSet
import net.minecraft.network.FriendlyByteBuf
import ru.lazyhat.compuktercraft.network.client.ChatTableClientMessage
import ru.lazyhat.compuktercraft.network.client.ClientNetworkContext
import ru.lazyhat.compuktercraft.network.client.ComputerTerminalClientMessage
import ru.lazyhat.compuktercraft.network.server.ComputerActionServerMessage
import ru.lazyhat.compuktercraft.network.server.KeyEventServerMessage
import ru.lazyhat.compuktercraft.network.server.MouseEventServerMessage
import ru.lazyhat.compuktercraft.network.server.PasteEventComputerMessage
import ru.lazyhat.compuktercraft.network.server.ServerNetworkContext
import ru.lazyhat.compuktercraft.platform.NetworkHandler

/**
 * List of all [MessageType]s provided by CC: Tweaked.
 *
 * @see PlatformHelper The platform helper is used to send packets.
 */
object NetworkMessages {
    private val seenIds: IntSet = IntOpenHashSet()
    private val seenChannel: MutableSet<String> = HashSet()
    private val serverMessages: MutableList<MessageType<out NetworkMessage<ServerNetworkContext>>> = ArrayList()
    private val clientMessages: MutableList<MessageType<out NetworkMessage<ClientNetworkContext>>> = ArrayList()

    val COMPUTER_ACTION: MessageType<ComputerActionServerMessage> =
        registerServerbound(
            0,
            "computer_action",
            ComputerActionServerMessage::class.java,
            FriendlyByteBuf.Reader(::ComputerActionServerMessage),
        )
    val KEY_EVENT: MessageType<KeyEventServerMessage> =
        registerServerbound(
            1,
            "key_event",
            KeyEventServerMessage::class.java,
            FriendlyByteBuf.Reader(::KeyEventServerMessage),
        )
    val MOUSE_EVENT: MessageType<MouseEventServerMessage> =
        registerServerbound(
            2,
            "mouse_event",
            MouseEventServerMessage::class.java,
            FriendlyByteBuf.Reader(::MouseEventServerMessage),
        )
    val PASTE_EVENT: MessageType<PasteEventComputerMessage> =
        registerServerbound(
            3,
            "paste_event",
            PasteEventComputerMessage::class.java,
            FriendlyByteBuf.Reader(::PasteEventComputerMessage),
        )
//    val UPLOAD_FILE: MessageType<UploadFileMessage>? =
//        NetworkMessages.registerServerbound<T>(
//            4,
//            "upload_file",
//            UploadFileMessage::class.java,
//            FriendlyByteBuf.Reader { UploadFileMessage() },
//        )

    val CHAT_TABLE: MessageType<ChatTableClientMessage> =
        registerClientbound(
            10,
            "chat_table",
            ChatTableClientMessage::class.java,
            FriendlyByteBuf.Reader(::ChatTableClientMessage),
        )

//    val POCKET_COMPUTER_DATA: MessageType<PocketComputerDataMessage>? =
//        NetworkMessages.registerClientbound<T>(
//            11,
//            "pocket_computer_data",
//            PocketComputerDataMessage::class.java,
//            FriendlyByteBuf.Reader { PocketComputerDataMessage() },
//        )
//    val POCKET_COMPUTER_DELETED: MessageType<PocketComputerDeletedClientMessage>? =
//        NetworkMessages.registerClientbound<T>(
//            12,
//            "pocket_computer_deleted",
//            PocketComputerDeletedClientMessage::class.java,
//            FriendlyByteBuf.Reader { PocketComputerDeletedClientMessage() },
//        )
    val COMPUTER_TERMINAL: MessageType<ComputerTerminalClientMessage> =
        registerClientbound(
            13,
            "computer_terminal",
            ComputerTerminalClientMessage::class.java,
            FriendlyByteBuf.Reader(::ComputerTerminalClientMessage),
        )
//    val PLAY_RECORD: MessageType<PlayRecordClientMessage>? =
//        NetworkMessages.registerClientbound<T>(
//            14,
//            "play_record",
//            PlayRecordClientMessage::class.java,
//            FriendlyByteBuf.Reader { PlayRecordClientMessage() },
//        )
//    val MONITOR_CLIENT: MessageType<MonitorClientMessage>? =
//        NetworkMessages.registerClientbound<T>(
//            15,
//            "monitor_client",
//            MonitorClientMessage::class.java,
//            FriendlyByteBuf.Reader { MonitorClientMessage() },
//        )
//    val SPEAKER_AUDIO: MessageType<SpeakerAudioClientMessage>? =
//        NetworkMessages.registerClientbound<T>(
//            16,
//            "speaker_audio",
//            SpeakerAudioClientMessage::class.java,
//            FriendlyByteBuf.Reader { SpeakerAudioClientMessage() },
//        )
//    val SPEAKER_MOVE: MessageType<SpeakerMoveClientMessage>? =
//        NetworkMessages.registerClientbound<T>(
//            17,
//            "speaker_move",
//            SpeakerMoveClientMessage::class.java,
//            FriendlyByteBuf.Reader { SpeakerMoveClientMessage() },
//        )
//    val SPEAKER_PLAY: MessageType<SpeakerPlayClientMessage>? =
//        NetworkMessages.registerClientbound<T>(
//            18,
//            "speaker_play",
//            SpeakerPlayClientMessage::class.java,
//            FriendlyByteBuf.Reader { SpeakerPlayClientMessage() },
//        )
//    val SPEAKER_STOP: MessageType<SpeakerStopClientMessage>? =
//        NetworkMessages.registerClientbound<T>(
//            19,
//            "speaker_stop",
//            SpeakerStopClientMessage::class.java,
//            FriendlyByteBuf.Reader { SpeakerStopClientMessage() },
//        )
//    val UPLOAD_RESULT: MessageType<UploadResultMessage> =
//        NetworkMessages.registerClientbound(
//            20,
//            "upload_result",
//            UploadResultMessage::class.java,
//            FriendlyByteBuf.Reader(::UploadResultMessage),
//        )
//    val UPGRADES_LOADED: MessageType<UpgradesLoadedMessage>? =
//        NetworkMessages.registerClientbound<T>(
//            21,
//            "upgrades_loaded",
//            UpgradesLoadedMessage::class.java,
//            FriendlyByteBuf.Reader { UpgradesLoadedMessage() },
//        )

    @Suppress("UNCHECKED_CAST")
    private fun <C, T : NetworkMessage<C>> register(
        messages: MutableList<MessageType<out NetworkMessage<C>>>,
        id: Int,
        channel: String,
        klass: Class<T>,
        reader: FriendlyByteBuf.Reader<T>,
    ): MessageType<T> {
        require(seenIds.add(id)) { "Duplicate id $id" }
        require(seenChannel.add(channel)) { "Duplicate channel $channel" }
        val type = NetworkHandler.MessageTypeImpl(id, klass, reader)
        messages.add(type)
        return type
    }

    private fun <T : NetworkMessage<ServerNetworkContext>> registerServerbound(
        id: Int,
        channel: String,
        klass: Class<T>,
        reader: FriendlyByteBuf.Reader<T>,
    ): MessageType<T> = register(serverMessages, id, channel, klass, reader)

    private fun <T : NetworkMessage<ClientNetworkContext>> registerClientbound(
        id: Int,
        channel: String,
        klass: Class<T>,
        reader: FriendlyByteBuf.Reader<T>,
    ): MessageType<T> = register(clientMessages, id, channel, klass, reader)

    /**
     * Get all serverbound message types.
     *
     * @return An unmodifiable sequence of all serverbound message types.
     */
    val serverbound: List<MessageType<out NetworkMessage<ServerNetworkContext>>>
        get() = serverMessages.toList()

    /**
     * Get all clientbound message types.
     *
     * @return An unmodifiable sequence of all clientbound message types.
     */
    val clientbound: List<MessageType<out NetworkMessage<ClientNetworkContext>>>
        get() = clientMessages.toList()
}
