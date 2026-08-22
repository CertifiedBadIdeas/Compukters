/*
 * The Compukters Developers
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

package ru.lazyhat.compukters.compiler.worker.controller

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.createDirectories

data class WorkerMeasurementReport(
    val payloadBytes: Long,
    val payloadSha256: String,
    val coldStartupMillis: Long,
    val firstCompilationMillis: Long,
    val warmCompilationMillis: Long,
    val workerHeapBytes: ULong,
    val workerMetaspaceBytes: ULong,
    val peakRssBytes: Long?,
    val requestBytes: Int,
    val resultBytes: Int,
    val artifactBytes: Int,
) {
    fun toJson(): String =
        buildString {
            appendLine("{")
            appendLine("  \"payloadBytes\": $payloadBytes,")
            appendLine("  \"payloadSha256\": \"$payloadSha256\",")
            appendLine("  \"coldStartupMillis\": $coldStartupMillis,")
            appendLine("  \"firstCompilationMillis\": $firstCompilationMillis,")
            appendLine("  \"warmCompilationMillis\": $warmCompilationMillis,")
            appendLine("  \"workerHeapBytes\": $workerHeapBytes,")
            appendLine("  \"workerMetaspaceBytes\": $workerMetaspaceBytes,")
            appendLine("  \"peakRssBytes\": ${peakRssBytes ?: "null"},")
            appendLine("  \"requestBytes\": $requestBytes,")
            appendLine("  \"resultBytes\": $resultBytes,")
            appendLine("  \"artifactBytes\": $artifactBytes")
            appendLine("}")
        }
}

object WorkerMetrics {
    fun write(
        report: WorkerMeasurementReport,
        target: Path,
    ) {
        val normalized = target.toAbsolutePath().normalize()
        normalized.parent.createDirectories()
        val staging = normalized.resolveSibling(".${normalized.fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(staging, report.toJson())
            try {
                Files.move(staging, normalized, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging, normalized, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(staging)
        }
    }
}
