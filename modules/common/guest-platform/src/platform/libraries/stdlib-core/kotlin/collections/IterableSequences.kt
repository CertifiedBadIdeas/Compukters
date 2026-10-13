/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.collections

import kotlin.sequences.Sequence

/**
 * Borrows this iterable as a lazy sequence without traversing or copying it.
 * Each traversal requests a new source iterator; later mutations and invalidation follow
 * the iterable's own contract. Wrappers and iterators use ordinary managed heap quotas.
 */
public fun <T> Iterable<T>.asSequence(): Sequence<T> = IterableSequence<T>(this)

internal class IterableSequence<T>(private val source: Iterable<T>) : Sequence<T> {
    override fun iterator(): Iterator<T> = source.iterator()
}
