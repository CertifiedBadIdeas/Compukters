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

package ru.lazyhat.compukters.compiler.worker.k2

import ru.lazyhat.compukters.compiler.k2.engine.library.LoadedPlatformLibraries
import ru.lazyhat.compukters.compiler.worker.protocol.TrustedBundleIdentity

/** Retains only the most recent validated selection within one pinned compiler adapter. */
internal class PreparedPlatformLibraryCache {
    private var entry: Entry? = null

    fun get(
        identities: List<TrustedBundleIdentity>,
        load: () -> LoadedPlatformLibraries,
    ): LoadedPlatformLibraries {
        val key = identities.toSet()
        entry?.let { cached ->
            if (cached.key == key) return cached.libraries
        }
        // Release the previous selection before loading its replacement; failed loads are never cached.
        entry = null
        return load().also { entry = Entry(key, it) }
    }

    private data class Entry(
        val key: Set<TrustedBundleIdentity>,
        val libraries: LoadedPlatformLibraries,
    )
}
