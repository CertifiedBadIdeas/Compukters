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

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import java.nio.file.Path

internal data class CompilationCacheEntry(
    val identity: Hash256,
    val directory: Path,
    val artifactHash: Hash256,
    val artifactBytes: Int,
    var lastAccessSequence: Long,
    var recencyDirty: Boolean = false,
)
