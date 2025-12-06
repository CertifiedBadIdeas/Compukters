// SPDX-FileCopyrightText: 2022 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.network.client

import ru.lazyhat.compuktercraft.gui.TerminalState
import ru.lazyhat.compuktercraft.network.text.TableBuilder

/**
 * The context under which clientbound packets are evaluated.
 */
interface ClientNetworkContext {
    fun handleChatTable(table: TableBuilder)

    fun handleComputerTerminal(
        containerId: Int,
        terminal: TerminalState,
    )

//    fun handleMonitorData(
//        pos: BlockPos?,
//        terminal: TerminalState?,
//    )
//
//    fun handlePlayRecord(
//        pos: BlockPos,
//        sound: SoundEvent?,
//        name: String?,
//    )
//
//    fun handlePocketComputerData(
//        instanceId: UUID,
//        state: ComputerState,
//        lightState: Int,
//        terminal: TerminalState?,
//    )
//
//    fun handlePocketComputerDeleted(instanceId: UUID)
//
//    fun handleSpeakerAudio(
//        source: UUID?,
//        position: SpeakerPosition.Message?,
//        volume: Float,
//        audio: EncodedAudio?,
//    )
//
//    fun handleSpeakerMove(
//        source: UUID?,
//        position: SpeakerPosition.Message?,
//    )
//
//    fun handleSpeakerPlay(
//        source: UUID?,
//        position: SpeakerPosition.Message?,
//        sound: ResourceLocation?,
//        volume: Float,
//        pitch: Float,
//    )
//
//    fun handleSpeakerStop(source: UUID?)
//
//    fun handleUploadResult(
//        containerId: Int,
//        result: UploadResult?,
//        @Nullable
//        errorMessage: Component?,
//    )
}
