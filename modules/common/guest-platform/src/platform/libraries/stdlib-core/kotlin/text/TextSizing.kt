/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.text

/** Counts and indexes refer to UTF-16 code units, including individual surrogate halves. */
public fun String.take(n: Int): String {
    require(n >= 0)
    return substring(0, if (n > length) length else n)
}

public fun String.drop(n: Int): String {
    require(n >= 0)
    return substring(if (n > length) length else n, length)
}

public fun String.takeLast(n: Int): String {
    require(n >= 0)
    return substring(if (n > length) 0 else length - n, length)
}

public fun String.dropLast(n: Int): String {
    require(n >= 0)
    return substring(0, if (n > length) 0 else length - n)
}

public fun String.padStart(length: Int): String = padStart(length, ' ')

public fun String.padStart(
    length: Int,
    padChar: Char,
): String {
    require(length >= 0)
    if (length <= this.length) return this
    val output = CharArray(length)
    val padding = length - this.length
    var index = 0
    while (index < length) {
        output[index] = if (index < padding) padChar else this[index - padding]
        index += 1
    }
    return String(output, 0, output.size)
}

public fun String.padEnd(length: Int): String = padEnd(length, ' ')

public fun String.padEnd(
    length: Int,
    padChar: Char,
): String {
    require(length >= 0)
    if (length <= this.length) return this
    val output = CharArray(length)
    var index = 0
    while (index < length) {
        output[index] = if (index < this.length) this[index] else padChar
        index += 1
    }
    return String(output, 0, output.size)
}

public fun String.repeat(n: Int): String {
    require(n >= 0)
    if (n == 0 || length == 0) return ""
    if (n == 1) return this
    require(n <= Int.MAX_VALUE / length)
    val output = CharArray(length * n)
    var index = 0
    while (index < output.size) {
        output[index] = this[index % length]
        index += 1
    }
    return String(output, 0, output.size)
}
