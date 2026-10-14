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

package ru.lazyhat.compukters.impl.ide

import ru.lazyhat.compukters.ide.analysis.PeripheralObservation
import ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget
import ru.lazyhat.compukters.impl.ide.target.IdeTargetReference
import ru.lazyhat.compukters.impl.ide.target.IdeTargetReply
import java.util.concurrent.CompletableFuture

/** A bounded live observation, independent of the Devices window. All mutation is on the client owner thread. */
internal class IdePeripheralObserver(
    private val request: (IdeTargetReference) -> CompletableFuture<IdeTargetReply>?,
    private val publish: (IdeAttachedTarget?, List<PeripheralObservation>?, Boolean) -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) : AutoCloseable {
    private var target: IdeAttachedTarget? = null
    private var pending: CompletableFuture<IdeTargetReply>? = null
    private var requestedAt = 0L
    private var refreshAt = 0L
    private var expiresAt = 0L
    private var observed = false

    fun tick(current: IdeAttachedTarget?) {
        val now = nowMillis()
        if (current != target) {
            pending?.cancel(false)
            pending = null
            target = current
            observed = false
            refreshAt = now
            publish(current, null, false)
        }
        if (current == null) return
        if (observed && now >= expiresAt) {
            observed = false
            publish(current, null, false)
        }
        pending?.let { future ->
            if (now - requestedAt >= TIMEOUT_MILLIS && !future.isDone) future.cancel(false)
            if (future.isDone) {
                pending = null
                refreshAt = now + REFRESH_MILLIS
                val reply =
                    if (now - requestedAt <
                        TIMEOUT_MILLIS
                    ) {
                        runCatching { future.join() }.getOrNull() as? IdeTargetReply.NamedPeripherals
                    } else {
                        null
                    }
                observed = reply != null
                expiresAt = now + TIMEOUT_MILLIS
                publish(
                    current,
                    reply?.entries?.map { PeripheralObservation(it.name, it.lookupTypes, it.availability) },
                    reply?.truncated == false,
                )
            }
        }
        if (pending == null && now >= refreshAt) {
            requestedAt = now
            refreshAt = now + REFRESH_MILLIS
            pending = runCatching { request(IdeTargetReference(current.id, current.profile)) }.getOrNull()
            if (pending == null) {
                observed = false
                publish(current, null, false)
            }
        }
    }

    override fun close() {
        pending?.cancel(false)
        pending = null
        target = null
        observed = false
        publish(null, null, false)
    }

    companion object {
        const val REFRESH_MILLIS = 1_000L
        const val TIMEOUT_MILLIS = 5_000L
    }
}
