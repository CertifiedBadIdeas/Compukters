/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package compukter.filesystem

/** An immutable absolute lexical path, independent of storage and native capabilities. */
public value class Path(
    public val value: String,
) {
    init {
        validatePathSyntax(value)
    }

    public val name: String?
        get() = if (value == "/") null else value.substring(value.lastIndexOf('/') + 1, value.length)

    public val parent: Path?
        get() {
            if (value == "/") return null
            val end = value.lastIndexOf('/')
            return Path(if (end == 0) "/" else value.substring(0, end))
        }

    /** Combines this explicit base with a relative path without normalizing dot components or accessing storage. */
    public fun resolve(relative: String): Path {
        requirePathSyntax(!relative.startsWith("/"), "resolve requires a relative path")
        if (relative.isEmpty()) return this
        return Path(if (value == "/") "/$relative" else "$value/$relative")
    }

    /** Removes dot components without accessing storage; attempts to ascend above root stay at root. */
    public fun normalize(): Path {
        if (value.indexOf("/.") < 0) return this
        val output = CharArray(value.length)
        val previousLengths = IntArray(value.length)
        output[0] = '/'
        var length = 1
        var depth = 0
        var start = 1
        var index = 1
        while (index <= value.length) {
            if (index == value.length || value[index] == '/') {
                val componentLength = index - start
                if (componentLength == 1 && value[start] == '.') {
                    // The current directory contributes no component.
                } else if (componentLength == 2 && value[start] == '.' && value[start + 1] == '.') {
                    if (depth > 0) {
                        depth--
                        length = previousLengths[depth]
                    }
                } else {
                    previousLengths[depth] = length
                    depth++
                    if (length > 1) output[length++] = '/'
                    var source = start
                    while (source < index) output[length++] = value[source++]
                }
                start = index + 1
            }
            index++
        }
        return if (length == value.length) this else Path(String(output, 0, length))
    }

    override fun toString(): String = value
}

/** Converts an absolute path string to a lexical value without accessing storage. */
public fun String.toPath(): Path = Path(this)

private fun validatePathSyntax(value: String) {
    requirePathSyntax(value.startsWith("/"), "path must be absolute")
    var index = 0
    while (index < value.length) {
        val unit = value[index].code
        requirePathSyntax(unit != 0, "path contains NUL")
        if (unit in 55296..56319) {
            requirePathSyntax(index + 1 < value.length && value[index + 1].code in 56320..57343, "path contains malformed Unicode")
            index += 2
        } else {
            requirePathSyntax(unit !in 56320..57343, "path contains malformed Unicode")
            index++
        }
    }
    if (value == "/") return
    var start = 1
    index = 1
    while (index <= value.length) {
        if (index == value.length || value[index] == '/') {
            val length = index - start
            requirePathSyntax(length > 0, "path contains an empty component")
            start = index + 1
        }
        index++
    }
}

private fun requirePathSyntax(
    valid: Boolean,
    message: String,
) {
    if (!valid) throw IllegalArgumentException(message)
}
