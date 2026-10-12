/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.text

import kotlin.collections.CharIterator

/** Visits UTF-16 code units in order. Stored iterators are supported and own their position. */
public operator fun CharSequence.iterator(): CharIterator = TextCharIterator(this)

private class TextCharIterator(
    private val text: CharSequence,
) : CharIterator() {
    private var index: Int = 0

    public override fun hasNext(): Boolean = index < text.length

    public override fun next(): Char = nextChar()

    public override fun nextChar(): Char {
        if (!hasNext()) throw NoSuchElementException("String iterator is exhausted")
        val result = text[index]
        index += 1
        return result
    }
}
