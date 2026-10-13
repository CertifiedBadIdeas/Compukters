/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

@file:OptIn(kotlin.contracts.ExperimentalContracts::class)

package compukter.io

import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/** A portable Guest resource. Closing is idempotent and does not implicitly commit writes. */
public interface Closeable {
    public fun close()
}

/** Closes after the block. A body failure takes precedence over a cleanup failure. */
public inline fun <T : Closeable, R> T.use(block: (T) -> R): R {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    var failed = false
    try {
        return block(this)
    } catch (failure: Throwable) {
        failed = true
        throw failure
    } finally {
        if (failed) {
            try {
                close()
            } catch (cleanupFailure: Throwable) {
                // Throwable suppression is not part of the portable Guest contract.
            }
        } else {
            close()
        }
    }
}
