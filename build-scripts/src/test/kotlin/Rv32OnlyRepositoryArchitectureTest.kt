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

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class Rv32OnlyRepositoryArchitectureTest {
    private val retiredIsa = "k" + "16"
    private val retiredProduct = "kraft" + "16"

    @Test
    fun retiredMarkerInTrackedPathIsRejected() {
        val violations =
            Rv32OnlyRepositoryGuard.entryViolations(
                RepositoryEntry("host/$retiredIsa-vm/src/lib.rs", ByteArray(0)),
            )

        assertTrue(violations.any { it.contains("tracked path") })
    }

    @Test
    fun retiredMarkerInActiveTextIsRejected() {
        val violations =
            Rv32OnlyRepositoryGuard.entryViolations(
                RepositoryEntry("docs/current.md", "Use $retiredProduct here".encodeToByteArray()),
            )

        assertTrue(violations.any { it.contains("active text") })
    }

    @Test
    fun futureKraftOsGuestPathIsAccepted() {
        val violations =
            Rv32OnlyRepositoryGuard.entryViolations(
                RepositoryEntry("guest/kraftos/src/main.rs", "RV32 guest".encodeToByteArray()),
            )

        assertTrue(violations.isEmpty(), violations.joinToString(separator = "\n"))
    }

    @Test
    fun exactHistoricalPathIsAccepted() {
        val violations =
            Rv32OnlyRepositoryGuard.entryViolations(
                RepositoryEntry(
                    "docs/architecture-decisions/0001-adopt-rv32.md",
                    "The retired ISA was $retiredIsa".encodeToByteArray(),
                ),
            )

        assertTrue(violations.isEmpty(), violations.joinToString(separator = "\n"))
    }

    @Test
    fun changedImmutableArtifactIsRejected() {
        val expected = mapOf("docs/benchmarks/evidence.txt" to SHA256_ABC)
        val files = mapOf("docs/benchmarks/evidence.txt" to "changed".encodeToByteArray())

        val violations = Rv32OnlyRepositoryGuard.immutableArtifactViolations(files, expected)

        assertTrue(violations.any { it.contains("SHA-256 mismatch") })
    }

    @Test
    fun missingImmutableArtifactIsRejected() {
        val expected = mapOf("docs/benchmarks/evidence.txt" to SHA256_ABC)

        val violations = Rv32OnlyRepositoryGuard.immutableArtifactViolations(emptyMap(), expected)

        assertTrue(violations.any { it.contains("missing") })
    }

    @Test
    fun liveTrackedRepositoryConformsToRv32OnlyBoundary() {
        val violations = Rv32OnlyRepositoryGuard.validateRepository(repositoryRoot())

        assertTrue(violations.isEmpty(), violations.joinToString(separator = "\n"))
    }

    private fun repositoryRoot(): Path {
        val candidates =
            listOf(
                Path.of(System.getProperty("user.dir"), "..").normalize(),
                Path.of(System.getProperty("user.dir")),
            )
        return candidates.firstOrNull { Files.exists(it.resolve("settings.gradle.kts")) }
            ?: error("Could not locate repository root from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val SHA256_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}
