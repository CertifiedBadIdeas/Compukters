/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin

/**
 * Calls [action] with each index from zero until [times], or not at all for non-positive counts.
 *
 * Indexes are visited in ascending order. Exceptions from [action] stop iteration and
 * propagate to the caller. The inline action supports non-local returns.
 *
 * ```kotlin
 * repeat(3) { index -> println(index) }
 * ```
 *
 * @param times number of calls; zero and negative counts do nothing.
 * @param action callback receiving the current zero-based index.
 */
public inline fun repeat(
    times: Int,
    action: (Int) -> Unit,
) {
    var index = 0
    while (index < times) {
        action(index)
        index += 1
    }
}

/**
 * Calls [block] with this value and returns its result.
 *
 * The value is passed as an argument (`it` in an unnamed lambda), even when it is null.
 * Use a safe call, `value?.let { ... }`, to skip the block for a null receiver.
 * The block runs once; its exceptions propagate and it supports non-local returns.
 *
 * ```kotlin
 * val size = "hello".let { it.length }
 * ```
 *
 * @param T type of the receiver, which may be nullable.
 * @param R type of the block result.
 * @receiver value passed to the block.
 * @param block transformation to run once.
 * @return the value produced by [block].
 * @see run
 * @see also
 */
public inline fun <T, R> T.let(block: (T) -> R): R = block(this)

/**
 * Calls [block] with this value as its receiver and returns its result.
 *
 * Inside the block, `this` refers to this value. Nullable receivers are passed through;
 * a safe call skips the block when the receiver is null. The block runs once;
 * its exceptions propagate and it supports non-local returns.
 *
 * @param T type of the receiver, which may be nullable.
 * @param R type of the block result.
 * @receiver value available as `this` in the block.
 * @param block computation to run once.
 * @return the value produced by [block].
 * @see let
 * @see apply
 */
public inline fun <T, R> T.run(block: T.() -> R): R = block()

/**
 * Calls [block] without a receiver and returns its result.
 *
 * Use this overload to group statements into an expression with local variables.
 * The block runs once; its exceptions propagate and it supports non-local returns.
 *
 * ```kotlin
 * val sum = run {
 *     val left = 2
 *     val right = 3
 *     left + right
 * }
 * ```
 *
 * @param R type of the block result.
 * @param block computation to run once.
 * @return the value produced by [block].
 */
public inline fun <R> run(block: () -> R): R = block()

/**
 * Calls [block] with [receiver] as its receiver and returns its result.
 *
 * Inside the block, `this` refers to [receiver], including when that value is null.
 * The block runs once; its exceptions propagate and it supports non-local returns.
 *
 * @param T type of [receiver], which may be nullable.
 * @param R type of the block result.
 * @param receiver value available as `this` in the block.
 * @param block computation to run once.
 * @return the value produced by [block].
 * @see run
 */
public inline fun <T, R> with(
    receiver: T,
    block: T.() -> R,
): R = receiver.block()

/**
 * Calls [block] with this value as its receiver and returns this value.
 *
 * Use this function to configure a value: `this` inside the block is the original
 * receiver, and the block result is discarded. Nullable receivers are passed through.
 * The block runs once; its exceptions propagate and it supports non-local returns.
 *
 * ```kotlin
 * val text = StringBuilder().apply {
 *     append("Hello")
 *     append('!')
 * }.toString()
 * ```
 *
 * @param T type of the receiver, which may be nullable.
 * @receiver value to configure and return.
 * @param block action to run once with this receiver.
 * @return the original receiver, without copying it.
 * @see also
 * @see run
 */
public inline fun <T> T.apply(block: T.() -> Unit): T {
    block()
    return this
}

/**
 * Calls [block] with this value and returns this value.
 *
 * Use this function for an additional action, such as logging. The value is passed as
 * an argument (`it` in an unnamed lambda), even when it is null; the block result is
 * discarded. The block runs once; its exceptions propagate and it supports non-local returns.
 *
 * @param T type of the receiver, which may be nullable.
 * @receiver value passed to the block and returned.
 * @param block action to run once.
 * @return the original receiver, without copying it.
 * @see apply
 * @see let
 */
public inline fun <T> T.also(block: (T) -> Unit): T {
    block(this)
    return this
}

/**
 * Returns this value when [predicate] is true, or null otherwise.
 *
 * The predicate receives the original value, including null, and runs once.
 * Its exceptions propagate and the inline predicate supports non-local returns.
 * A null result can mean either rejection or acceptance of a null receiver.
 *
 * ```kotlin
 * val positive = 5.takeIf { it > 0 }
 * ```
 *
 * @param T type of the receiver, which may be nullable.
 * @receiver value to test.
 * @param predicate test that returns true to retain the value.
 * @return the original receiver when accepted, or null when rejected.
 * @see takeUnless
 */
public inline fun <T> T.takeIf(predicate: (T) -> Boolean): T? = if (predicate(this)) this else null

/**
 * Returns this value when [predicate] is false, or null otherwise.
 *
 * The predicate receives the original value, including null, and runs once.
 * Its exceptions propagate and the inline predicate supports non-local returns.
 * A null result can mean either rejection or acceptance of a null receiver.
 *
 * @param T type of the receiver, which may be nullable.
 * @receiver value to test.
 * @param predicate test that returns true to reject the value.
 * @return the original receiver when the predicate is false, or null when it is true.
 * @see takeIf
 */
public inline fun <T> T.takeUnless(predicate: (T) -> Boolean): T? = if (!predicate(this)) this else null
