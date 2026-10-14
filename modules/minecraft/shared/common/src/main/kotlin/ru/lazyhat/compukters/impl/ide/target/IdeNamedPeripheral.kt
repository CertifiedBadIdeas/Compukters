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

package ru.lazyhat.compukters.impl.ide.target

import ru.lazyhat.compukters.ide.analysis.PeripheralAvailability

/** Immutable bounded discovery snapshot; item IDs are resolved only by the physical client. */
data class IdeNamedPeripheral(
    val name: String,
    val itemId: String,
    val duplicate: Boolean = false,
    val reference: IdePeripheralReference? = null,
    val lookupTypes: List<String> = emptyList(),
    val availability: PeripheralAvailability = PeripheralAvailability.Available,
) {
    init {
        require(name.isNotBlank() && name.length <= 64 && name.none(Char::isISOControl))
        require(lookupTypes.size <= 8 && lookupTypes.distinct().size == lookupTypes.size)
        require(lookupTypes.all { it.length <= 256 && it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) })
        require(itemId.length <= 256 && itemId.matches(Regex("[a-z0-9_.-]+:[a-z0-9/._-]+")))
    }
}

/** Exact logical instance and kind; positions may change without silently selecting another device. */
data class IdePeripheralReference(
    val instance: java.util.UUID,
    val providerId: String,
    val deviceKey: String,
) {
    init {
        require(providerId.isNotBlank() && providerId.length <= 128 && providerId.none(Char::isISOControl))
        require(deviceKey.isNotBlank() && deviceKey.length <= 128 && deviceKey.none(Char::isISOControl))
    }
}
