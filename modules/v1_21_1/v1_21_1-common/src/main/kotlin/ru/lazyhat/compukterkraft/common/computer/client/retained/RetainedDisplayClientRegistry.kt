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

package ru.lazyhat.compukterkraft.common.computer.client.retained

import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedDisplayApplyResult
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedDisplayReplica
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedDisplayResyncReason
import ru.lazyhat.compukterkraft.core.device.display.retained.RetainedDisplayState

enum class RetainedDisplayViewKind {
    MENU,
    WORLD,
}

data class RetainedDisplayObserverKey(
    val identity: Any,
    val viewKind: RetainedDisplayViewKind,
)

sealed interface RetainedDisplayClientInstallResult {
    data class Installed(
        val acknowledgement: ByteArray,
    ) : RetainedDisplayClientInstallResult

    data class ResyncRequired(
        val reason: RetainedDisplayResyncReason,
        val request: ByteArray?,
    ) : RetainedDisplayClientInstallResult
}

class RetainedDisplayClientRegistry(
    private val nativeCacheFactory: (UInt) -> RetainedDisplayNativeCache,
) : AutoCloseable {
    private val entries = mutableMapOf<UInt, RetainedDisplayClientEntry>()

    fun attach(
        computerId: UInt,
        observerKey: RetainedDisplayObserverKey,
    ): RetainedDisplayObserverHandle {
        require(computerId != 0u) { "Retained display computer ID must be non-zero" }
        val existing = entries[computerId]
        val entry = existing ?: RetainedDisplayClientEntry(computerId, nativeCacheFactory(computerId))
        if (existing == null) entries[computerId] = entry
        try {
            require(entry.attach(observerKey)) { "Retained display observer is already attached: $observerKey" }
        } catch (failure: Throwable) {
            if (existing == null && entry.observerCount == 0) {
                entries.remove(computerId, entry)
                try {
                    entry.close()
                } catch (closeFailure: Throwable) {
                    if (failure !== closeFailure) failure.addSuppressed(closeFailure)
                }
            }
            throw failure
        }
        return RetainedDisplayObserverHandle(entry, observerKey.viewKind) {
            var releaseFailure: Throwable? = null
            val lastObserver =
                try {
                    entry.detach(observerKey)
                } catch (failure: Throwable) {
                    releaseFailure = failure
                    entry.observerCount == 0
                }
            if (lastObserver) {
                entries.remove(computerId, entry)
                try {
                    entry.close()
                } catch (closeFailure: Throwable) {
                    if (releaseFailure == null) {
                        releaseFailure = closeFailure
                    } else if (releaseFailure !== closeFailure) {
                        releaseFailure.addSuppressed(closeFailure)
                    }
                }
            }
            releaseFailure?.let { throw it }
        }
    }

    fun entry(computerId: UInt): RetainedDisplayClientEntry? = entries[computerId]

    override fun close() {
        val closing = entries.values.toList()
        entries.clear()
        cleanupAll(closing, RetainedDisplayClientEntry::close)
    }
}

class RetainedDisplayObserverHandle internal constructor(
    val entry: RetainedDisplayClientEntry,
    private val viewKind: RetainedDisplayViewKind,
    private val detach: () -> Unit,
) : AutoCloseable {
    private var closed = false

    fun presentation(): MinecraftRetainedNativePresentation? = entry.presentation(viewKind)

    override fun close() {
        if (closed) return
        closed = true
        detach()
    }
}

class RetainedDisplayClientEntry internal constructor(
    val computerId: UInt,
    private val nativeCache: RetainedDisplayNativeCache,
) : AutoCloseable {
    private val replica = RetainedDisplayReplica()
    private val observers = mutableSetOf<RetainedDisplayObserverKey>()
    private var closed = false

    val state: RetainedDisplayState?
        get() = replica.state

    val observerCount: Int
        get() = observers.size

    internal fun presentation(viewKind: RetainedDisplayViewKind): MinecraftRetainedNativePresentation? = nativeCache.presentation(viewKind)

    internal fun attach(observerKey: RetainedDisplayObserverKey): Boolean {
        check(!closed) { "Retained display client entry is closed" }
        if (observerKey in observers) return false
        nativeCache.retainView(observerKey.viewKind, replica.state)
        observers += observerKey
        return true
    }

    internal fun detach(observerKey: RetainedDisplayObserverKey): Boolean {
        if (closed || !observers.remove(observerKey)) return false
        nativeCache.releaseView(observerKey.viewKind)
        return observers.isEmpty()
    }

    fun apply(payload: ByteArray): RetainedDisplayClientInstallResult {
        check(!closed) { "Retained display client entry is closed" }
        return when (val result = replica.apply(payload)) {
            is RetainedDisplayApplyResult.ResyncRequired -> {
                RetainedDisplayClientInstallResult.ResyncRequired(result.reason, result.request)
            }

            is RetainedDisplayApplyResult.Installed -> {
                try {
                    check(result.state.computerId == computerId) {
                        "Retained display payload computer ID ${result.state.computerId} does not match entry $computerId"
                    }
                    nativeCache.install(result.state, result.damage)
                    RetainedDisplayClientInstallResult.Installed(result.acknowledgement)
                } catch (installFailure: Throwable) {
                    val request = replica.clearAndRequestResync(RetainedDisplayResyncReason.ATOMIC_INSTALL_FAILED)
                    try {
                        nativeCache.invalidate()
                    } catch (invalidateFailure: Throwable) {
                        if (invalidateFailure !== installFailure) invalidateFailure.addSuppressed(installFailure)
                        throw invalidateFailure
                    }
                    RetainedDisplayClientInstallResult.ResyncRequired(
                        RetainedDisplayResyncReason.ATOMIC_INSTALL_FAILED,
                        request,
                    )
                }
            }
        }
    }

    fun invalidateRenderResources(): RetainedDisplayClientInstallResult.ResyncRequired {
        check(!closed) { "Retained display client entry is closed" }
        val result =
            RetainedDisplayClientInstallResult.ResyncRequired(
                RetainedDisplayResyncReason.RENDER_RESOURCE_LOST,
                replica.clearAndRequestResync(RetainedDisplayResyncReason.RENDER_RESOURCE_LOST),
            )
        nativeCache.invalidate()
        return result
    }

    override fun close() {
        if (closed) return
        closed = true
        observers.clear()
        nativeCache.close()
    }
}
