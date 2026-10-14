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

/** Advisory observations never become compiler/build failures. Incomplete lists cannot prove absence. */
object PeripheralDiagnostics {
    fun collect(
        lookups: List<PeripheralLookup>,
        observations: List<PeripheralObservation>,
        complete: Boolean,
    ): List<EditorDiagnostic> {
        if (!complete) return emptyList()
        val byName = observations.groupBy { it.name }
        return lookups
            .asSequence()
            .mapNotNull { lookup ->
                val named = byName[lookup.name.trim().lowercase(java.util.Locale.ROOT)].orEmpty()
                val compatible = named.filter { lookup.type in it.types }
                val available = compatible.filter { it.availability == PeripheralAvailability.Available }
                val problem =
                    when {
                        available.size == 1 -> null

                        available.size > 1 -> "More than one reachable device uses this name"

                        named.any { it.types.isEmpty() && it.availability == PeripheralAvailability.Available } -> null

                        compatible.isNotEmpty() -> reason(compatible.first().availability)

                        named.any { it.types.isEmpty() } -> null

                        // An unknown addon contract cannot prove a type mismatch.
                        named.isNotEmpty() -> "The device does not support ${lookup.type.substringAfterLast('.')}"

                        else -> "No device with this name is reachable in the attached computer's network"
                    } ?: return@mapNotNull null
                val displayedName =
                    lookup.name
                        .take(64)
                        .map { if (it.isISOControl()) ' ' else it }
                        .joinToString("")
                EditorDiagnostic(
                    if (lookup.optional) EditorDiagnosticSeverity.Info else EditorDiagnosticSeverity.Warning,
                    "Peripheral '$displayedName': $problem. Current network state${if (lookup.optional) "; optional lookup" else ""}.",
                    lookup.path,
                    lookup.range,
                    if (lookup.optional) EditorDiagnosticHighlight.None else EditorDiagnosticHighlight.Standard,
                )
            }.take(EditorPresentationLimits().maxDiagnostics)
            .toList()
    }

    private fun reason(value: PeripheralAvailability): String =
        when (value) {
            PeripheralAvailability.Available -> "The device is unavailable for this lookup"
            PeripheralAvailability.OutOfRange -> "The device is outside the computer's access radius"
            PeripheralAvailability.OtherDimension -> "The device is in another dimension"
            PeripheralAvailability.Unloaded -> "The device's chunk is not loaded"
            PeripheralAvailability.Replaced -> "The registered device has been removed or replaced"
            PeripheralAvailability.Unavailable -> "The registered device is currently unavailable"
        }
}
