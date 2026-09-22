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

internal enum class PeripheralCandidateStatus {
    INVALID,
    FREE,
    TARGET,
    CONFLICT,
}

internal data class PeripheralInspectionEntry(
    val name: String,
    val providerId: String,
    val deviceKey: String,
    val duplicate: Boolean,
)

internal sealed interface PeripheralInspection {
    data class Complete(
        val entries: List<PeripheralInspectionEntry>,
        val totalNamedDevices: Int,
        val truncated: Boolean,
        val nameCounts: Map<String, Int>,
        val targetName: String?,
        val candidateStatus: PeripheralCandidateStatus,
    ) : PeripheralInspection

    data class LimitExceeded(
        val limit: PeripheralCableLimit,
        val maximum: Int,
    ) : PeripheralInspection
}

internal fun inspectPeripheralComponent(
    traversal: PeripheralCableTraversal<*, PeripheralDeviceIdentity>,
    directory: PeripheralDeviceDirectory,
    target: PeripheralDeviceIdentity?,
    candidate: String,
    maximumEntries: Int = 64,
): PeripheralInspection {
    require(maximumEntries >= 0) { "maximumEntries must not be negative" }
    if (traversal is PeripheralCableTraversal.LimitExceeded) {
        return PeripheralInspection.LimitExceeded(traversal.limit, traversal.maximum)
    }
    val reachable = (traversal as PeripheralCableTraversal.Complete).contacts
    val named =
        reachable.mapNotNull { identity ->
            directory.nameOf(identity)?.let { name -> identity to name }
        }
    val nameCounts = named.groupingBy(Pair<PeripheralDeviceIdentity, String>::second).eachCount().toSortedMap()
    val duplicateNames = nameCounts.filterValues { it > 1 }.keys
    val entries =
        named
            .sortedWith(
                compareBy<Pair<PeripheralDeviceIdentity, String>>(
                    Pair<PeripheralDeviceIdentity, String>::second,
                    { it.first.providerId },
                    { it.first.deviceKey },
                    { it.first.anchor.asLong() },
                ),
            ).take(maximumEntries)
            .map { (identity, name) ->
                PeripheralInspectionEntry(name, identity.providerId, identity.deviceKey, name in duplicateNames)
            }
    return PeripheralInspection.Complete(
        entries = entries,
        totalNamedDevices = named.size,
        truncated = named.size > entries.size,
        nameCounts = nameCounts,
        targetName = target?.let(directory::nameOf),
        candidateStatus = evaluatePeripheralCandidate(candidate, nameCounts, target?.let(directory::nameOf)),
    )
}

internal fun evaluatePeripheralCandidate(
    candidate: String,
    nameCounts: Map<String, Int>,
    targetName: String?,
): PeripheralCandidateStatus {
    val normalized = runCatching { normalizePeripheralName(candidate) }.getOrNull() ?: return PeripheralCandidateStatus.INVALID
    val matches = nameCounts[normalized] ?: 0
    return when {
        matches == 0 -> PeripheralCandidateStatus.FREE
        targetName == normalized && matches == 1 -> PeripheralCandidateStatus.TARGET
        else -> PeripheralCandidateStatus.CONFLICT
    }
}

internal fun assignPeripheralName(
    directory: PeripheralDeviceDirectory,
    reachable: Set<PeripheralDeviceIdentity>,
    target: PeripheralDeviceIdentity,
    requestedName: String,
): PeripheralCandidateStatus {
    val normalized = runCatching { normalizePeripheralName(requestedName) }.getOrNull() ?: return PeripheralCandidateStatus.INVALID
    if (reachable.any { identity -> identity != target && directory.nameOf(identity) == normalized }) {
        return PeripheralCandidateStatus.CONFLICT
    }
    directory.setName(target, normalized)
    return PeripheralCandidateStatus.TARGET
}
