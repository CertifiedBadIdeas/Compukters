/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin

/**
 * Validates an argument precondition.
 *
 * Returns normally when [value] is true. On failure, the exception message is
 * `Failed requirement.`. Use the lazy-message overload to provide more context.
 *
 * @param value condition that must be true.
 * @throws IllegalArgumentException if [value] is false.
 * @see check
 */
public fun require(value: Boolean) {
    if (!value) throw IllegalArgumentException("Failed requirement.")
}

/**
 * Validates an argument precondition, computing a message only on failure.
 *
 * When [value] is true, [lazyMessage] is never called. Otherwise it runs once and its
 * result is converted using `toString()`. Exceptions from the callback or conversion
 * propagate unchanged. The inline callback supports non-local returns.
 *
 * ```kotlin
 * require(count >= 0) { "Negative count: $count" }
 * ```
 *
 * @param value condition that must be true.
 * @param lazyMessage callback producing a non-null message value on failure.
 * @throws IllegalArgumentException if [value] is false and message construction completes normally.
 */
public inline fun require(
    value: Boolean,
    lazyMessage: () -> Any,
) {
    if (!value) throw IllegalArgumentException(lazyMessage().toString())
}

/**
 * Validates a state precondition.
 *
 * Returns normally when [value] is true. On failure, the exception message is
 * `Check failed.`. Use the lazy-message overload to provide more context.
 *
 * @param value condition that must be true.
 * @throws IllegalStateException if [value] is false.
 * @see require
 */
public fun check(value: Boolean) {
    if (!value) throw IllegalStateException("Check failed.")
}

/**
 * Validates a state precondition, computing a message only on failure.
 *
 * When [value] is true, [lazyMessage] is never called. Otherwise it runs once and its
 * result is converted using `toString()`. Exceptions from the callback or conversion
 * propagate unchanged. The inline callback supports non-local returns.
 *
 * ```kotlin
 * check(count >= 0) { "Negative count: $count" }
 * ```
 *
 * @param value condition that must be true.
 * @param lazyMessage callback producing a non-null message value on failure.
 * @throws IllegalStateException if [value] is false and message construction completes normally.
 */
public inline fun check(
    value: Boolean,
    lazyMessage: () -> Any,
) {
    if (!value) throw IllegalStateException(lazyMessage().toString())
}

/**
 * Stops normal execution by throwing an [IllegalStateException].
 *
 * The return type `Nothing` allows this call in any expression that needs a value.
 *
 * ```kotlin
 * val value = candidate ?: error("Missing value")
 * ```
 *
 * @param message description of the invalid state.
 * @throws IllegalStateException always, with [message] as its detail message.
 */
public fun error(message: String): Nothing = throw IllegalStateException(message)

/**
 * Creates an empty array with the requested element type.
 *
 * ```kotlin
 * val names: Array<String> = emptyArray()
 * ```
 *
 * @param T type of array elements.
 * @return an array with size zero.
 */
public external fun <T> emptyArray(): Array<T>
