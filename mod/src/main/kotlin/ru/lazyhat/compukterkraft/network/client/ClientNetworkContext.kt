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

import ru.lazyhat.compukterkraft.gui.TerminalState
import ru.lazyhat.compukterkraft.network.text.TableBuilder

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
