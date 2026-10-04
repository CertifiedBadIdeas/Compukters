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

package ru.lazyhat.compukters.integration.propulsion

import java.util.IdentityHashMap

/** Server-confined ownership; only actively controlled endpoints participate in tick validation. */
internal class ThrusterControlLeases<K : Any> {
    private data class Lease(
        val owner: Any,
        val valid: () -> Boolean,
        val release: () -> Unit,
    )

    private val leases = IdentityHashMap<K, Lease>()

    fun claim(
        key: K,
        owner: Any,
        valid: () -> Boolean,
        release: () -> Unit,
    ): Boolean {
        val current = leases[key]
        if (current != null && !current.valid()) {
            leases.remove(key)
            current.release()
        }
        val existing = leases[key]
        if (existing != null) return existing.owner === owner
        leases[key] = Lease(owner, valid, release)
        return true
    }

    fun release(
        key: K,
        owner: Any,
    ) {
        val lease = leases[key] ?: return
        if (lease.owner !== owner) return
        leases.remove(key)
        lease.release()
    }

    fun releaseOwner(owner: Any) {
        leases.entries
            .filter { it.value.owner === owner }
            .map { it.key }
            .forEach { release(it, owner) }
    }

    fun reap() {
        leases.entries.filter { !it.value.valid() }.map { it.key }.forEach { key ->
            leases.remove(key)?.release?.invoke()
        }
    }

    fun contains(key: K): Boolean = leases.containsKey(key)

    fun releaseAll() {
        val previous = leases.values.toList()
        leases.clear()
        previous.forEach { it.release() }
    }
}
