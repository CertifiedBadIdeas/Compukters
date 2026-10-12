/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

@file:OptIn(kotlin.contracts.ExperimentalContracts::class)

package kotlin.text

import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Builds mutable text by appending UTF-16 code units.
 *
 * [length] counts code units, so a supplementary Unicode character occupies two indexes.
 * Capacity is the allocated buffer size, not the content length. [clear] retains that buffer;
 * [toString] and [subSequence] return independent immutable snapshots.
 * Buffer growth and snapshots are subject to the Guest heap quota.
 *
 * @param capacity initial buffer size in UTF-16 code units; defaults to 16.
 * @constructor Creates an empty builder with the requested initial capacity.
 * @throws IllegalArgumentException if [capacity] is negative.
 * @see buildString
 */
public class StringBuilder(
    capacity: Int = 16,
) : CharSequence {
    private var buffer: CharArray = CharArray(checkedTextCapacity(capacity))
    private var count: Int = 0
    /** Number of UTF-16 code units currently stored, excluding unused capacity. */
    public override val length: Int get() = count

    /**
     * Returns the number of UTF-16 code units the buffer can hold without growing.
     *
     * @return the allocated capacity, which is at least [length].
     */
    public fun capacity(): Int = buffer.size

    /**
     * Grows the buffer when [minimumCapacity] exceeds the current capacity.
     *
     * Existing text and [length] are preserved. Requests at or below the current capacity,
     * including negative requests, do nothing. The resulting capacity may exceed the request.
     *
     * @param minimumCapacity desired buffer size in UTF-16 code units.
     */
    public fun ensureCapacity(minimumCapacity: Int) {
        if (minimumCapacity <= buffer.size) return
        val grown = buffer.size.toLong() * 2L + 2L
        val target = if (grown > Int.MAX_VALUE.toLong()) Int.MAX_VALUE else grown.toInt()
        buffer = buffer.copyOf(if (minimumCapacity > target) minimumCapacity else target)
    }

    /**
     * Reads a UTF-16 code unit at [index].
     *
     * @param index zero-based position in the current content.
     * @return the code unit at [index], which may be one half of a surrogate pair.
     * @throws IndexOutOfBoundsException if [index] is negative or is at least [length].
     */
    public override operator fun get(index: Int): Char {
        checkIndex(index)
        return buffer[index]
    }

    /**
     * Copies the selected range into an immutable String.
     *
     * Later changes to this builder do not affect the result. An empty range is allowed.
     *
     * @param startIndex first UTF-16 code unit to include.
     * @param endIndex exclusive end of the range.
     * @return an independent snapshot of the selected content.
     * @throws IndexOutOfBoundsException if the range is reversed or extends outside `0..length`.
     */
    public override fun subSequence(
        startIndex: Int,
        endIndex: Int,
    ): String {
        if (startIndex < 0 || endIndex < startIndex || endIndex > length) {
            throw IndexOutOfBoundsException("StringBuilder range outside content")
        }
        return String(buffer, startIndex, endIndex - startIndex)
    }

    /**
     * Replaces one UTF-16 code unit without changing the content length.
     *
     * @param index zero-based position to replace.
     * @param value replacement code unit; surrogate pairs are not validated.
     * @throws IndexOutOfBoundsException if [index] is negative or is at least [length].
     */
    public operator fun set(
        index: Int,
        value: Char,
    ) {
        checkIndex(index)
        buffer[index] = value
    }

    /**
     * Appends one UTF-16 code unit, growing the buffer if needed.
     *
     * @param value code unit to append.
     * @return this builder for chained calls.
     * @throws IllegalArgumentException if the resulting length would exceed [Int.MAX_VALUE].
     */
    public fun append(value: Char): StringBuilder {
        reserve(1)
        buffer[count] = value
        count += 1
        return this
    }

    /**
     * Copies all code units from [value] to the end of this builder.
     *
     * Appending this builder to itself copies its original content once.
     *
     * @param value text to append; null appends the four characters `null`.
     * @return this builder for chained calls.
     * @throws IllegalArgumentException if the resulting length would exceed [Int.MAX_VALUE].
     */
    public fun append(value: CharSequence?): StringBuilder {
        val text = value ?: "null"
        return append(text, 0, text.length)
    }

    /**
     * Copies a range of [value] to the end of this builder.
     *
     * When [value] is null, the range selects from the four-character string `null`.
     * Self-append reads the selected original range before changing [length].
     *
     * @param value source text, or null to use `null` as the source.
     * @param startIndex first source UTF-16 code unit to include.
     * @param endIndex exclusive end of the source range; may equal [startIndex].
     * @return this builder for chained calls.
     * @throws IllegalArgumentException if the range is reversed, is outside the source,
     * or the resulting length would exceed [Int.MAX_VALUE].
     */
    public fun append(
        value: CharSequence?,
        startIndex: Int,
        endIndex: Int,
    ): StringBuilder {
        val text = value ?: "null"
        require(startIndex >= 0 && endIndex >= startIndex && endIndex <= text.length)
        val count = endIndex - startIndex
        reserve(count)
        val destination = this.count
        var index = 0
        while (index < count) {
            buffer[destination + index] = text[startIndex + index]
            index += 1
        }
        this.count += count
        return this
    }

    /**
     * Copies all code units from [value] to the end of this builder.
     *
     * Later changes to the array do not affect the appended text.
     *
     * @param value source array of UTF-16 code units.
     * @return this builder for chained calls.
     * @throws IllegalArgumentException if the resulting length would exceed [Int.MAX_VALUE].
     */
    public fun append(value: CharArray): StringBuilder = appendRange(value, 0, value.size)

    /**
     * Copies a range of [value] to the end of this builder.
     *
     * Later changes to the array do not affect the appended text. An empty range is allowed.
     *
     * @param value source array of UTF-16 code units.
     * @param startIndex first array index to include.
     * @param endIndex exclusive end of the array range.
     * @return this builder for chained calls.
     * @throws IllegalArgumentException if the range is reversed, is outside the array,
     * or the resulting length would exceed [Int.MAX_VALUE].
     */
    public fun appendRange(
        value: CharArray,
        startIndex: Int,
        endIndex: Int,
    ): StringBuilder {
        require(startIndex >= 0 && endIndex >= startIndex && endIndex <= value.size)
        val count = endIndex - startIndex
        reserve(count)
        value.copyInto(buffer, this.count, startIndex, endIndex)
        this.count += count
        return this
    }

    /**
     * Converts [value] to text and appends that text.
     *
     * A non-null value uses its `toString()` implementation; exceptions from that conversion
     * propagate to the caller. Null appends the four characters `null`.
     *
     * @param value object to convert and append.
     * @return this builder for chained calls.
     * @throws IllegalArgumentException if the resulting length would exceed [Int.MAX_VALUE].
     */
    public fun append(value: Any?): StringBuilder = append(value.toString())

    /**
     * Removes all content while retaining the allocated capacity for reuse.
     *
     * Existing String snapshots remain unchanged.
     *
     * @return this builder, with [length] set to zero.
     */
    public fun clear(): StringBuilder {
        count = 0
        return this
    }

    /**
     * Changes the content length, growing the buffer if needed.
     *
     * Shortening discards the suffix without shrinking capacity. Extending fills every
     * newly exposed position with the NUL code unit (`\u0000`), including after shortening.
     *
     * @param newLength desired number of UTF-16 code units.
     * @throws IllegalArgumentException if [newLength] is negative.
     */
    public fun setLength(newLength: Int) {
        require(newLength >= 0)
        ensureCapacity(newLength)
        var index = count
        while (index < newLength) {
            buffer[index] = '\u0000'
            index += 1
        }
        count = newLength
    }

    /**
     * Appends a line feed (`\n`).
     *
     * The line separator is always a single LF code unit.
     *
     * @return this builder for chained calls.
     * @throws IllegalArgumentException if appending the text or separator would exceed [Int.MAX_VALUE].
     */
    public fun appendLine(): StringBuilder = append('\n')

    /**
     * Appends [value] followed by a line feed (`\n`).
     *
     * The line separator is always a single LF code unit.
     *
     * @param value UTF-16 code unit to append.
     * @return this builder for chained calls.
     * @throws IllegalArgumentException if appending the text or separator would exceed [Int.MAX_VALUE].
     */
    public fun appendLine(value: Char): StringBuilder = append(value).append('\n')

    /**
     * Copies [value] followed by a line feed (`\n`). Null appends `null` before the line feed.
     *
     * The line separator is always a single LF code unit.
     *
     * @param value source text to copy, or null.
     * @return this builder for chained calls.
     * @throws IllegalArgumentException if appending the text or separator would exceed [Int.MAX_VALUE].
     */
    public fun appendLine(value: CharSequence?): StringBuilder = append(value).append('\n')

    /**
     * Converts [value] to text, appends it, then appends a line feed (`\n`).
     *
     * The line separator is always a single LF code unit.
     * Null becomes `null`; exceptions from a non-null value's `toString()` propagate.
     *
     * @param value object to convert and append.
     * @return this builder for chained calls.
     * @throws IllegalArgumentException if appending the text or separator would exceed [Int.MAX_VALUE].
     */
    public fun appendLine(value: Any?): StringBuilder = append(value).append('\n')

    /**
     * Copies the current content into an immutable String.
     *
     * @return an independent snapshot unaffected by subsequent changes to this builder.
     */
    public override fun toString(): String = String(buffer, 0, length)

    private fun reserve(count: Int) {
        require(count >= 0 && count <= Int.MAX_VALUE - this.count)
        ensureCapacity(this.count + count)
    }

    private fun checkIndex(index: Int) {
        if (index < 0 || index >= length) throw IndexOutOfBoundsException("StringBuilder index outside content")
    }
}

/**
 * Creates a mutable builder containing a copy of [content].
 *
 * @param content initial text; its UTF-16 length determines the initial capacity.
 * @return a new builder whose length and capacity equal `content.length`.
 */
public fun StringBuilder(content: String): StringBuilder = StringBuilder(content.length).append(content)

private fun checkedTextCapacity(capacity: Int): Int {
    require(capacity >= 0)
    return capacity
}

/**
 * Builds an immutable String using an initially empty [StringBuilder].
 *
 * The action runs once with the builder as its receiver. Exceptions from the action
 * propagate to the caller. As an inline action, it supports non-local returns.
 *
 * ```kotlin
 * val message = buildString {
 *     append("Hello, ")
 *     append("world!")
 * }
 * ```
 *
 * @param builderAction action that writes the result into the builder.
 * @return an independent snapshot of the content after the action completes normally.
 */
public inline fun buildString(builderAction: StringBuilder.() -> Unit): String {
    contract { callsInPlace(builderAction, InvocationKind.EXACTLY_ONCE) }
    val builder = StringBuilder()
    builder.builderAction()
    return builder.toString()
}

/**
 * Builds an immutable String with a builder sized for the expected content.
 *
 * Capacity is an allocation hint, not a length limit: the builder starts empty and
 * grows as needed. The action runs once with the builder as its receiver; its exceptions
 * propagate to the caller. The inline action supports non-local returns.
 *
 * @param capacity initial buffer size in UTF-16 code units.
 * @param builderAction action that writes the result into the builder.
 * @return an independent snapshot of the content after the action completes normally.
 * @throws IllegalArgumentException if [capacity] is negative, before the action runs.
 */
public inline fun buildString(
    capacity: Int,
    builderAction: StringBuilder.() -> Unit,
): String {
    contract { callsInPlace(builderAction, InvocationKind.EXACTLY_ONCE) }
    val builder = StringBuilder(capacity)
    builder.builderAction()
    return builder.toString()
}
