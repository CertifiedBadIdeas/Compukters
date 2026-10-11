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

    /** Combines this explicit base with a relative name; no storage access or dot traversal. */
    public fun resolve(relative: String): Path {
        requirePathSyntax(!relative.startsWith("/"), "resolve requires a relative path")
        if (relative.isEmpty()) return this
        return Path(if (value == "/") "/$relative" else "$value/$relative")
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
            requirePathSyntax(
                !(value[start] == '.' && (length == 1 || (length == 2 && value[start + 1] == '.'))),
                "path contains dot traversal",
            )
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
