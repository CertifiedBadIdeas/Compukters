/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin

/** Calls [action] with each index from zero until [times], or not at all for non-positive counts. */
public inline fun repeat(times: Int, action: (Int) -> Unit) {
    var index = 0
    while (index < times) {
        action(index)
        index += 1
    }
}

/** Calls [block] with this value and returns its result. */
public inline fun <T, R> T.let(block: (T) -> R): R = block(this)

/** Calls [block] with this value as its receiver and returns its result. */
public inline fun <T, R> T.run(block: T.() -> R): R = block()

/** Calls [block] without a receiver and returns its result. */
public inline fun <R> run(block: () -> R): R = block()

/** Calls [block] with [receiver] as its receiver and returns its result. */
public inline fun <T, R> with(receiver: T, block: T.() -> R): R = receiver.block()

/** Calls [block] with this value as its receiver and returns this value. */
public inline fun <T> T.apply(block: T.() -> Unit): T {
    block()
    return this
}

/** Calls [block] with this value and returns this value. */
public inline fun <T> T.also(block: (T) -> Unit): T {
    block(this)
    return this
}

/** Returns this value when [predicate] is true, or null otherwise. */
public inline fun <T> T.takeIf(predicate: (T) -> Boolean): T? = if (predicate(this)) this else null

/** Returns this value when [predicate] is false, or null otherwise. */
public inline fun <T> T.takeUnless(predicate: (T) -> Boolean): T? = if (!predicate(this)) this else null
