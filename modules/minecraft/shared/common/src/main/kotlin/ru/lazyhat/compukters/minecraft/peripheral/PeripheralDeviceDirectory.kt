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

import net.minecraft.core.BlockPos
import java.util.Locale

internal data class PeripheralDeviceIdentity(
    val providerId: String,
    val dimension: String,
    val anchor: BlockPos,
    val deviceKey: String,
)

internal data class PeripheralDeviceName(
    val identity: PeripheralDeviceIdentity,
    val name: String,
)

internal sealed interface PeripheralNameLookup {
    data object Missing : PeripheralNameLookup

    data class Found(
        val identity: PeripheralDeviceIdentity,
    ) : PeripheralNameLookup

    data class Ambiguous(
        val identities: List<PeripheralDeviceIdentity>,
    ) : PeripheralNameLookup
}

internal class PeripheralDeviceDirectory(
    initialEntries: Iterable<PeripheralDeviceName> = emptyList(),
) {
    private val names = linkedMapOf<PeripheralDeviceIdentity, String>()

    init {
        initialEntries.forEach { entry ->
            val normalized = normalizePeripheralName(entry.name)
            require(names.put(entry.identity, normalized) == null) { "duplicate peripheral device identity" }
        }
    }

    fun nameOf(identity: PeripheralDeviceIdentity): String? = names[identity]

    fun setName(
        identity: PeripheralDeviceIdentity,
        requestedName: String,
    ): String = normalizePeripheralName(requestedName).also { normalized -> names[identity] = normalized }

    fun clearName(identity: PeripheralDeviceIdentity): String? = names.remove(identity)

    fun lookup(
        requestedName: String,
        reachable: Iterable<PeripheralDeviceIdentity>,
    ): PeripheralNameLookup {
        val normalized = normalizePeripheralName(requestedName)
        val matches = reachable.distinct().filter { identity -> names[identity] == normalized }
        return when (matches.size) {
            0 -> PeripheralNameLookup.Missing
            1 -> PeripheralNameLookup.Found(matches.single())
            else -> PeripheralNameLookup.Ambiguous(matches)
        }
    }

    fun snapshot(): List<PeripheralDeviceName> = names.map { (identity, name) -> PeripheralDeviceName(identity, name) }

}

internal fun normalizePeripheralName(requestedName: String): String {
    val normalized = requestedName.trim().lowercase(Locale.ROOT)
    require(PERIPHERAL_NAME_PATTERN.matches(normalized)) {
        "peripheral name must match ${PERIPHERAL_NAME_PATTERN.pattern}"
    }
    return normalized
}

private val PERIPHERAL_NAME_PATTERN = Regex("[a-z][a-z0-9_-]{0,31}")
