/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.text

/** Replaces every oldChar with newChar, comparing case-sensitively. */
public fun String.replace(oldChar: Char, newChar: Char): String {
    if (oldChar == newChar || indexOf(oldChar) < 0) return this
    val output = CharArray(length)
    var index = 0
    while (index < length) {
        val char = this[index]
        output[index] = if (char == oldChar) newChar else char
        index += 1
    }
    return String(output, 0, output.size)
}

/** Replaces nonoverlapping oldValue matches. An empty oldValue inserts newValue at every UTF-16 boundary. */
public fun String.replace(oldValue: String, newValue: String): String {
    if (oldValue == newValue) return this
    val step = if (oldValue.length == 0) 1 else oldValue.length
    var matches = 0L
    var search = 0
    while (true) {
        val index = indexOf(oldValue, search)
        if (index < 0) break
        matches += 1L
        if (index == length) break
        search = index + step
    }
    if (matches == 0L) return this
    val outputLength = length.toLong() + matches * (newValue.length.toLong() - oldValue.length.toLong())
    require(outputLength >= 0L && outputLength <= Int.MAX_VALUE.toLong())
    val output = CharArray(outputLength.toInt())
    var source = 0
    var destination = 0
    search = 0
    while (true) {
        val index = indexOf(oldValue, search)
        if (index < 0) break
        while (source < index) {
            output[destination] = this[source]
            source += 1
            destination += 1
        }
        var replacement = 0
        while (replacement < newValue.length) {
            output[destination] = newValue[replacement]
            replacement += 1
            destination += 1
        }
        source = index + oldValue.length
        if (index == length) break
        search = index + step
    }
    while (source < length) {
        output[destination] = this[source]
        source += 1
        destination += 1
    }
    return String(output, 0, output.size)
}
