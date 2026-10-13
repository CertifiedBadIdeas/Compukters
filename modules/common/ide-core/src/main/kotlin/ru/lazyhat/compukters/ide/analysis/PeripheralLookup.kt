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

package ru.lazyhat.compukters.ide.analysis

import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.editor.EditorRange

/** Static source evidence, independent of the current world's network state. */
data class PeripheralLookup(
    val path: VirtualSourcePath,
    val range: EditorRange,
    val type: String,
    val name: String,
    val optional: Boolean,
) {
    init {
        VirtualSourcePath.kotlin(path.value)
        require(range.length > 0)
        require(type.length <= 256 && type.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")))
        require(strictUtf8Size(name) <= 256)
    }
}

enum class PeripheralAvailability {
    Available,
    OutOfRange,
    OtherDimension,
    Unloaded,
    Replaced,
    Unavailable,
}

data class PeripheralObservation(
    val name: String,
    val types: List<String>,
    val availability: PeripheralAvailability = PeripheralAvailability.Available,
)
