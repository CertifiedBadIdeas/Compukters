/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.display

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Owner-thread admission/completion; workers compute only captured immutable inputs.
 * Busy keys do not enqueue snapshots: the caller retains its authoritative newest source instead.
 */
class DisplayWorkQueue<K, R>(
    private val executor: Executor,
    private val dispatch: (() -> Unit) -> Unit,
    private val maximumJobs: Int = 16,
    private val maximumBytes: Long = 64L * 1024 * 1024,
) : AutoCloseable {
    private val pending = mutableMapOf<K, Any>()
    private var retainedBytes = 0L
    private var closed = false

    init {
        require(maximumJobs > 0 && maximumBytes > 0)
    }

    fun <I> submit(
        key: K,
        bytes: Long,
        capture: () -> I,
        compute: (I) -> R,
        complete: (Result<R>) -> Unit,
    ): Boolean {
        require(bytes >= 0)
        if (closed || key in pending || pending.size >= maximumJobs || bytes > maximumBytes - retainedBytes) return false
        val token = Any()
        pending[key] = token
        retainedBytes += bytes

        fun release() {
            pending.remove(key)
            retainedBytes -= bytes
        }
        val input =
            try {
                capture()
            } catch (failure: Throwable) {
                release()
                throw failure
            }
        try {
            executor.execute {
                val result = runCatching { compute(input) }
                try {
                    dispatch {
                        if (!closed && pending[key] === token) {
                            release()
                            complete(result)
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    // The owner has stopped; close() discards its outstanding admissions.
                }
            }
        } catch (_: RejectedExecutionException) {
            release()
            return false
        }
        return true
    }

    override fun close() {
        closed = true
        pending.clear()
        retainedBytes = 0
    }
}
