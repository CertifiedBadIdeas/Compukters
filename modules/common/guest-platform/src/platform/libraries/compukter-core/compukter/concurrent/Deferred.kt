/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package compukter.concurrent

/** A typed result of cooperative work. Repeated awaits retain the same value or task exception. */
public class Deferred<T> internal constructor(block: () -> T) {
    private var value: T? = null
    private val task: Task = Tasks.launch { value = block() }

    /** Blocks only the calling task. Other tasks continue while this result is pending. */
    @Suppress("UNCHECKED_CAST")
    public fun await(): T {
        task.join()
        return value as T
    }
}

/** Starts [block] in the existing bounded cooperative task scheduler. */
public fun <T> async(block: () -> T): Deferred<T> = Deferred(block)

/** Awaits in argument order; a failed await throws without cancelling the remaining tasks. */
public fun <T> awaitAll(vararg deferred: Deferred<T>): List<T> {
    val values = ArrayList<T>()
    for (item in deferred) values.add(item.await())
    return values
}

/** Awaits in list order and preserves that order in the returned values. */
public fun <T> awaitAll(deferred: List<Deferred<T>>): List<T> {
    val values = ArrayList<T>()
    for (item in deferred) values.add(item.await())
    return values
}
