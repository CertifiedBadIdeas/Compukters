/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin

public fun require(value: Boolean) {
    if (!value) throw IllegalArgumentException("Failed requirement.")
}

public inline fun require(value: Boolean, lazyMessage: () -> String) {
    if (!value) throw IllegalArgumentException(lazyMessage())
}

public fun check(value: Boolean) {
    if (!value) throw IllegalStateException("Check failed.")
}

public inline fun check(value: Boolean, lazyMessage: () -> String) {
    if (!value) throw IllegalStateException(lazyMessage())
}

public fun error(message: String): Nothing {
    throw IllegalStateException(message)
}

public external fun <T> emptyArray(): Array<T>
