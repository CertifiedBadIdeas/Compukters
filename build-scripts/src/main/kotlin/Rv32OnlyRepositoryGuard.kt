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

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

data class RepositoryEntry(
    val path: String,
    val bytes: ByteArray,
)

object Rv32OnlyRepositoryGuard {
    private val retiredIsa = "k" + "16"
    private val retiredProduct = "kraft" + "16"
    private val retiredMarkers = listOf(retiredIsa, retiredProduct)

    private val historicalTextPaths by lazy {
        setOf(
            ".agents/tmp/specs/2026-06-20/issue-319-${retiredIsa}-process-model-v2.md",
            ".agents/tmp/specs/2026-06-21/issue-332-${retiredIsa}-hosted-abi-v0.md",
            ".agents/tmp/specs/2026-06-21/issue-346-${retiredIsa}-shared-cpu-helper-runtime.md",
            "docs/architecture-decisions/0001-adopt-rv32.md",
        ) + immutableBenchmarkDigests.keys
    }

    private val textExtensions =
        setOf(
            "c",
            "gradle",
            "h",
            "java",
            "json",
            "kts",
            "kt",
            "md",
            "properties",
            "rs",
            "s",
            "sh",
            "toml",
            "txt",
            "xml",
            "yaml",
            "yml",
        )

    fun entryViolations(entry: RepositoryEntry): List<String> {
        if (entry.path in historicalTextPaths) {
            return emptyList()
        }

        val violations = mutableListOf<String>()
        val normalizedPath = entry.path.lowercase()
        if (retiredMarkers.any(normalizedPath::contains)) {
            violations += "Retired VM marker in tracked path: ${entry.path}"
        }

        if (isTextLike(entry.path)) {
            val text = entry.bytes.toString(StandardCharsets.UTF_8).lowercase()
            if (retiredMarkers.any(text::contains)) {
                violations += "Retired VM marker in active text: ${entry.path}"
            }
        }
        return violations
    }

    fun immutableArtifactViolations(
        files: Map<String, ByteArray>,
        expectedDigests: Map<String, String>,
    ): List<String> =
        expectedDigests.mapNotNull { (path, expectedDigest) ->
            val bytes = files[path]
                ?: return@mapNotNull "Immutable historical artifact is missing: $path"
            val actualDigest = sha256(bytes)
            if (actualDigest == expectedDigest) {
                null
            } else {
                "Immutable historical artifact SHA-256 mismatch: $path (expected $expectedDigest, actual $actualDigest)"
            }
        }

    fun validateRepository(repositoryRoot: Path): List<String> {
        val entries = loadTrackedEntries(repositoryRoot)
        val files = entries.associate { it.path to it.bytes }
        return entries.flatMap(::entryViolations) +
            immutableArtifactViolations(files, immutableBenchmarkDigests)
    }

    private fun loadTrackedEntries(repositoryRoot: Path): List<RepositoryEntry> {
        val process =
            ProcessBuilder("git", "ls-files", "-z")
                .directory(repositoryRoot.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.readBytes()
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            "git ls-files failed with exit code $exitCode: ${output.toString(StandardCharsets.UTF_8)}"
        }

        return output
            .toString(StandardCharsets.UTF_8)
            .split('\u0000')
            .filter(String::isNotEmpty)
            .map { relativePath ->
                val path = repositoryRoot.resolve(relativePath)
                RepositoryEntry(
                    path = relativePath,
                    bytes = if (Files.isRegularFile(path)) Files.readAllBytes(path) else ByteArray(0),
                )
            }
    }

    private fun isTextLike(path: String): Boolean {
        val extension = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return extension in textExtensions
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private val immutableBenchmarkDigests =
        mapOf(
            "docs/benchmarks/${retiredIsa}-vm-baseline-2026-05-29.md" to
                "4af3c1c0abd6765d63bf2e5bf43aa6d905ae12c02a56c3245d321203c6fb573f",
            "docs/benchmarks/${retiredIsa}-vm-current.txt" to
                "549823ea3b0b01b9de3e53943d5454482a60266217fe9cb8565c9f6348b43528",
        )
}
