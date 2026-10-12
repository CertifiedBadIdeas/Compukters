/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.impl.display

import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket

/** Physical-client heartbeat. Track the physical key even when a GUI cancels world interaction. */
internal object DisplayPointerClient {
    private var held = false

    fun tick() {
        val client = Minecraft.getInstance()
        val next = client.options.keyUse.isDown
        val valid = client.screen == null && client.player?.mainHandItem?.isEmpty == true && client.player?.isShiftKeyDown == false
        if (client.connection != null && (next || held)) {
            client.connection?.send(ServerboundCustomPayloadPacket(DisplayPointerHeldPayload(next, !valid)))
        }
        held = next
    }
}
