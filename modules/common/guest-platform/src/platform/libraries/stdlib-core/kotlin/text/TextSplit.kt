/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.text

import kotlin.collections.ArrayList
import kotlin.collections.List

/** Splits on delimiter, retaining empty parts. Zero limit means no limit; positive limit keeps the remaining suffix. */
public fun String.split(delimiter: Char, limit: Int = 0): List<String> {
    require(limit >= 0)
    val result = ArrayList<String>()
    var start = 0
    while (limit == 0 || result.size < limit - 1) {
        val index = indexOf(delimiter, start)
        if (index < 0) break
        result.add(substring(start, index))
        start = index + 1
    }
    result.add(substring(start, length))
    return result
}

/** Splits on delimiter, retaining empty parts. An empty delimiter separates UTF-16 code units, including empty ends. */
public fun String.split(delimiter: String, limit: Int = 0): List<String> {
    require(limit >= 0)
    val result = ArrayList<String>()
    var start = 0
    var search = 0
    while (limit == 0 || result.size < limit - 1) {
        val index = indexOf(delimiter, search)
        if (index < 0) break
        result.add(substring(start, index))
        start = index + delimiter.length
        if (index == length) break
        search = if (delimiter.length == 0) index + 1 else start
    }
    result.add(substring(start, length))
    return result
}

/** Splits on CRLF, LF or CR, retaining empty lines including the final line. */
public fun String.lines(): List<String> {
    val result = ArrayList<String>()
    var start = 0
    var index = 0
    while (index < length) {
        val char = this[index]
        if (char == '\r' || char == '\n') {
            result.add(substring(start, index))
            index += 1
            if (char == '\r' && index < length && this[index] == '\n') index += 1
            start = index
        } else {
            index += 1
        }
    }
    result.add(substring(start, length))
    return result
}

