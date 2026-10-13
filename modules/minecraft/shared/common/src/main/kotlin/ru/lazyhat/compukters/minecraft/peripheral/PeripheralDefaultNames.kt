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

package ru.lazyhat.compukters.minecraft.peripheral

import java.util.Locale

internal fun defaultPeripheralNameBase(identity: PeripheralDeviceIdentity): String =
    when (identity.providerId) {
        PeripheralNetworkMember.COMPUTER_PROVIDER -> "computer"
        "compukters-display" -> "display"
        "compukters-redstone" -> "redstone_port"
        else -> identity.deviceKey.ifBlank { identity.providerId.substringAfterLast(':') }
    }

internal fun defaultPeripheralName(
    requestedBase: String,
    occupied: Set<String>,
): String {
    val sanitized = requestedBase.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_-]+"), "_").trim('_', '-')
    val base =
        when {
            sanitized.isEmpty() -> "device"
            sanitized.first() !in 'a'..'z' -> "device_$sanitized"
            else -> sanitized
        }.take(26)
    // Membership is bounded to 1024 entries, so a five-character suffix always has a free slot.
    return (1..PeripheralNetworkDirectory.MAXIMUM_MEMBERS + 1)
        .asSequence()
        .map { "${base}_$it" }
        .first { it !in occupied }
}
