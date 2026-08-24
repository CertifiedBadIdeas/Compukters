/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ru.lazyhat.compukters.compiler.runtime.cache

data class CompilationCachePolicy(
    val maximumEntries: Int = 1_024,
    val maximumArtifactBytes: Long = 256L * 1024 * 1024,
    val maximumSingleArtifactBytes: Int = 16 * 1024 * 1024,
    val maximumStartupEntries: Int = 4_096,
    val maximumMetadataBytes: Int = 4 * 1024,
) {
    init {
        require(maximumEntries > 0) { "cache entry limit must be positive" }
        require(maximumArtifactBytes > 0) { "cache byte limit must be positive" }
        require(maximumSingleArtifactBytes > 0) { "cache artifact limit must be positive" }
        require(maximumStartupEntries > 0) { "cache startup entry limit must be positive" }
        require(maximumMetadataBytes > 0) { "cache metadata limit must be positive" }
        require(maximumSingleArtifactBytes.toLong() <= maximumArtifactBytes) {
            "cache artifact limit must not exceed total byte limit"
        }
    }
}

data class CompilationCacheStats(
    val entries: Int,
    val artifactBytes: Long,
)

class CompilationCacheException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
