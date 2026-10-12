/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.text

/** Mutable UTF-16 text. Clearing retains capacity; toString returns an independent snapshot. */
public class StringBuilder(
    capacity: Int = 16,
) : CharSequence {
    private var buffer: CharArray = CharArray(checkedTextCapacity(capacity))
    private var count: Int = 0
    public override val length: Int get() = count

    public fun capacity(): Int = buffer.size

    public fun ensureCapacity(minimumCapacity: Int) {
        if (minimumCapacity <= buffer.size) return
        val grown = buffer.size.toLong() * 2L + 2L
        val target = if (grown > Int.MAX_VALUE.toLong()) Int.MAX_VALUE else grown.toInt()
        buffer = buffer.copyOf(if (minimumCapacity > target) minimumCapacity else target)
    }

    public override operator fun get(index: Int): Char {
        checkIndex(index)
        return buffer[index]
    }

    public override fun subSequence(
        startIndex: Int,
        endIndex: Int,
    ): String {
        if (startIndex < 0 || endIndex < startIndex || endIndex > length) {
            throw IndexOutOfBoundsException("StringBuilder range outside content")
        }
        return String(buffer, startIndex, endIndex - startIndex)
    }

    public operator fun set(
        index: Int,
        value: Char,
    ) {
        checkIndex(index)
        buffer[index] = value
    }

    public fun append(value: Char): StringBuilder {
        reserve(1)
        buffer[count] = value
        count += 1
        return this
    }

    /** Null appends the four characters "null". Self-append reads the original range. */
    public fun append(value: CharSequence?): StringBuilder {
        val text = value ?: "null"
        return append(text, 0, text.length)
    }

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

    public fun append(value: CharArray): StringBuilder = appendRange(value, 0, value.size)

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

    public fun append(value: Any?): StringBuilder = append(value.toString())

    public fun clear(): StringBuilder {
        count = 0
        return this
    }

    /** Extending the length fills the new range with NUL code units. */
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

    public fun appendLine(): StringBuilder = append('\n')

    public fun appendLine(value: Char): StringBuilder = append(value).append('\n')

    public fun appendLine(value: CharSequence?): StringBuilder = append(value).append('\n')

    public fun appendLine(value: Any?): StringBuilder = append(value).append('\n')

    public override fun toString(): String = String(buffer, 0, length)

    private fun reserve(count: Int) {
        require(count >= 0 && count <= Int.MAX_VALUE - this.count)
        ensureCapacity(this.count + count)
    }

    private fun checkIndex(index: Int) {
        if (index < 0 || index >= length) throw IndexOutOfBoundsException("StringBuilder index outside content")
    }
}

/** Creates a builder initialized with the given text. */
public fun StringBuilder(content: String): StringBuilder = StringBuilder(content.length).append(content)

private fun checkedTextCapacity(capacity: Int): Int {
    require(capacity >= 0)
    return capacity
}

public inline fun buildString(builderAction: StringBuilder.() -> Unit): String {
    val builder = StringBuilder()
    builder.builderAction()
    return builder.toString()
}

public inline fun buildString(
    capacity: Int,
    builderAction: StringBuilder.() -> Unit,
): String {
    val builder = StringBuilder(capacity)
    builder.builderAction()
    return builder.toString()
}
