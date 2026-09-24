/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.text

public fun String.startsWith(prefix: String): Boolean {
    if (prefix.length > length) return false
    var index = 0
    while (index < prefix.length) {
        if (this[index] != prefix[index]) return false
        index = index + 1
    }
    return true
}

public fun String.endsWith(suffix: String): Boolean {
    val offset = length - suffix.length
    if (offset < 0) return false
    var index = 0
    while (index < suffix.length) {
        if (this[offset + index] != suffix[index]) return false
        index = index + 1
    }
    return true
}

public fun String.contains(other: String): Boolean = indexOf(other) >= 0

public fun String.indexOf(other: String, startIndex: Int = 0): Int {
    var candidate = if (startIndex < 0) 0 else startIndex
    if (other.length == 0) return if (candidate > length) length else candidate
    val lastCandidate = length - other.length
    while (candidate <= lastCandidate) {
        var index = 0
        while (index < other.length && this[candidate + index] == other[index]) {
            index = index + 1
        }
        if (index == other.length) return candidate
        if (candidate == lastCandidate) return -1
        candidate = candidate + 1
    }
    return -1
}
