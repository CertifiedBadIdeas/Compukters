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

enum class MediaAssetCategory(
    val serialized: String,
) {
    ORIGINAL("original"),
    THIRD_PARTY("third-party"),
    GENERATED_DERIVATIVE("generated-derivative"),
}

data class MediaLicenseRecord(
    val path: String,
    val category: MediaAssetCategory,
    val origin: String,
    val copyright: String,
    val license: String,
    val provenance: String,
    val packaged: Boolean,
)

object MediaLicenseInventory {
    const val HEADER = "path\tcategory\torigin\tcopyright\tlicense\tprovenance\tpackaged"

    val extensions =
        setOf(
            "png",
            "webp",
            "jpg",
            "jpeg",
            "gif",
            "svg",
            "ico",
            "bdf",
            "ttf",
            "otf",
            "ogg",
            "wav",
            "flac",
            "mp3",
        )

    fun parse(text: String): List<MediaLicenseRecord> {
        val lines = text.replace("\r\n", "\n").split('\n')
        require(lines.firstOrNull() == HEADER) { "invalid media inventory header" }

        val records =
            lines
                .drop(1)
                .filter { it.isNotEmpty() }
                .mapIndexed { index, line -> parseRecord(index + 2, line) }
        val duplicatePaths =
            records
                .groupingBy { it.path }
                .eachCount()
                .filterValues { it > 1 }
                .keys
                .sorted()
        require(duplicatePaths.isEmpty()) {
            "duplicate media inventory path(s): ${duplicatePaths.joinToString()}"
        }
        return records
    }

    fun verify(
        records: List<MediaLicenseRecord>,
        discoveredPaths: Set<String>,
        existingPaths: Set<String>,
    ) {
        val inventoryPaths = records.mapTo(mutableSetOf()) { it.path }
        val unclassified = (discoveredPaths - inventoryPaths).sorted()
        val stale = (inventoryPaths - discoveredPaths).sorted()
        val missingAssets = records.map { it.path }.filterNot(existingPaths::contains).sorted()
        val missingProvenance = records.map { it.provenance }.distinct().filterNot(existingPaths::contains).sorted()

        check(unclassified.isEmpty()) {
            "unclassified media path(s): ${unclassified.joinToString()}"
        }
        check(stale.isEmpty()) {
            "stale media inventory path(s): ${stale.joinToString()}"
        }
        check(missingAssets.isEmpty()) {
            "missing media asset path(s): ${missingAssets.joinToString()}"
        }
        check(missingProvenance.isEmpty()) {
            "missing media provenance path(s): ${missingProvenance.joinToString()}"
        }
    }

    private fun parseRecord(
        lineNumber: Int,
        line: String,
    ): MediaLicenseRecord {
        val fields = line.split('\t')
        require(fields.size == 7) { "malformed media inventory row $lineNumber: expected 7 fields" }
        require(fields.all { it.isNotBlank() }) { "malformed media inventory row $lineNumber: blank field" }

        val path = validatedPath(fields[0], "asset", lineNumber)
        val provenance = validatedPath(fields[5], "provenance", lineNumber)
        val category =
            MediaAssetCategory.entries.singleOrNull { it.serialized == fields[1] }
                ?: throw IllegalArgumentException("unknown media category '${fields[1]}' on row $lineNumber")
        val packaged =
            when (fields[6]) {
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("unknown packaged value '${fields[6]}' on row $lineNumber")
            }

        return MediaLicenseRecord(
            path = path,
            category = category,
            origin = fields[2],
            copyright = fields[3],
            license = fields[4],
            provenance = provenance,
            packaged = packaged,
        )
    }

    private fun validatedPath(
        value: String,
        kind: String,
        lineNumber: Int,
    ): String {
        val segments = value.split('/')
        require(
            value.isNotEmpty() &&
                !value.startsWith('/') &&
                '\\' !in value &&
                segments.none { it.isEmpty() || it == "." || it == ".." },
        ) {
            "invalid $kind path '$value' on row $lineNumber: expected a normalized repository-relative path"
        }
        return value
    }
}
