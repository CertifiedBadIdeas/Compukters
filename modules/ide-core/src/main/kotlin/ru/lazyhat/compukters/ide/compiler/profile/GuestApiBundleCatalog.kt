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

package ru.lazyhat.compukters.ide.compiler.profile

import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.ide.project.ModuleId
import ru.lazyhat.compukters.ide.project.ResolvedModule
import java.security.MessageDigest
import java.util.Collections

enum class ApiBundleKind { API, ADDON }

data class ResolvedApiBundle(
    val module: ResolvedModule,
    val kind: ApiBundleKind,
    val content: BinaryValue,
)

data class GuestApiBundleCatalogLimits(
    val entries: Int = 256,
    val entryBytes: Int = 16 * 1024 * 1024,
    val totalBytes: Long = 64L * 1024 * 1024,
) {
    init {
        require(entries >= 0 && entryBytes >= 0 && totalBytes >= 0) { "Guest API bundle catalog limits must be non-negative" }
    }
}

class GuestApiBundleCatalog private constructor(
    entries: List<ResolvedApiBundle>,
) {
    val entries: List<ResolvedApiBundle> = Collections.unmodifiableList(entries.toList())
    private val byId = this.entries.associateBy { entry -> entry.module.id }

    fun find(id: ModuleId): ResolvedApiBundle? = byId[id]

    fun require(id: ModuleId): ResolvedApiBundle = checkNotNull(find(id)) { "Guest API bundle ${id.value} is unavailable" }

    companion object {
        fun of(
            entries: List<ResolvedApiBundle>,
            limits: GuestApiBundleCatalogLimits = GuestApiBundleCatalogLimits(),
        ): GuestApiBundleCatalog {
            require(entries.size <= limits.entries) { "Guest API bundle count exceeds limit" }
            var totalBytes = 0L
            entries.forEach { entry ->
                require(entry.content.size <= limits.entryBytes) { "Guest API bundle ${entry.module.id.value} exceeds byte limit" }
                totalBytes = Math.addExact(totalBytes, entry.content.size.toLong())
                require(totalBytes <= limits.totalBytes) { "Guest API bundle catalog exceeds total byte limit" }
                val actual = Hash256.of(MessageDigest.getInstance("SHA-256").digest(entry.content.toByteArray()))
                require(actual == entry.module.contentHash) { "Guest API bundle ${entry.module.id.value} content hash does not match" }
            }
            val sorted = entries.sortedWith(API_BUNDLE_COMPARATOR)
            require(sorted.zipWithNext().none { (left, right) -> left.module.id == right.module.id }) {
                "Guest API bundle IDs must be unique"
            }
            return GuestApiBundleCatalog(sorted)
        }
    }
}

internal val API_BUNDLE_COMPARATOR =
    Comparator<ResolvedApiBundle> { left, right -> compareModuleIds(left.module.id, right.module.id) }

internal fun compareModuleIds(
    left: ModuleId,
    right: ModuleId,
): Int {
    val provider = compareUtf8(left.provider, right.provider)
    return if (provider != 0) provider else compareUtf8(left.module, right.module)
}

private fun compareUtf8(
    left: String,
    right: String,
): Int {
    val leftBytes = left.encodeToByteArray()
    val rightBytes = right.encodeToByteArray()
    repeat(minOf(leftBytes.size, rightBytes.size)) { index ->
        val comparison = (leftBytes[index].toInt() and 0xff).compareTo(rightBytes[index].toInt() and 0xff)
        if (comparison != 0) return comparison
    }
    return leftBytes.size.compareTo(rightBytes.size)
}
