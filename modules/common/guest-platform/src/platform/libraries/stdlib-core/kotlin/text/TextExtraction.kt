/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.text

/** Returns the part before the first delimiter, or this string if absent. */
public fun String.substringBefore(delimiter: Char): String = substringBefore(delimiter, this)

/** Returns the part before the first delimiter, or missingDelimiterValue if absent. */
public fun String.substringBefore(delimiter: Char, missingDelimiterValue: String): String {
    val index = indexOf(delimiter)
    return if (index < 0) missingDelimiterValue else substring(0, index)
}

/** Returns the part before the first delimiter, or this string if absent. */
public fun String.substringBefore(delimiter: String): String = substringBefore(delimiter, this)

/** Returns the part before the first delimiter, or missingDelimiterValue if absent. */
public fun String.substringBefore(delimiter: String, missingDelimiterValue: String): String {
    val index = indexOf(delimiter)
    return if (index < 0) missingDelimiterValue else substring(0, index)
}

/** Returns the part after the first delimiter, or this string if absent. */
public fun String.substringAfter(delimiter: Char): String = substringAfter(delimiter, this)

/** Returns the part after the first delimiter, or missingDelimiterValue if absent. */
public fun String.substringAfter(delimiter: Char, missingDelimiterValue: String): String {
    val index = indexOf(delimiter)
    return if (index < 0) missingDelimiterValue else substring(index + 1, length)
}

/** Returns the part after the first delimiter, or this string if absent. */
public fun String.substringAfter(delimiter: String): String = substringAfter(delimiter, this)

/** Returns the part after the first delimiter, or missingDelimiterValue if absent. */
public fun String.substringAfter(delimiter: String, missingDelimiterValue: String): String {
    val index = indexOf(delimiter)
    return if (index < 0) missingDelimiterValue else substring(index + delimiter.length, length)
}

/** Returns the part before the last delimiter, or this string if absent. */
public fun String.substringBeforeLast(delimiter: Char): String = substringBeforeLast(delimiter, this)

/** Returns the part before the last delimiter, or missingDelimiterValue if absent. */
public fun String.substringBeforeLast(delimiter: Char, missingDelimiterValue: String): String {
    val index = lastIndexOf(delimiter)
    return if (index < 0) missingDelimiterValue else substring(0, index)
}

/** Returns the part before the last delimiter, or this string if absent. */
public fun String.substringBeforeLast(delimiter: String): String = substringBeforeLast(delimiter, this)

/** Returns the part before the last delimiter, or missingDelimiterValue if absent. */
public fun String.substringBeforeLast(delimiter: String, missingDelimiterValue: String): String {
    val index = lastIndexOf(delimiter)
    return if (index < 0) missingDelimiterValue else substring(0, index)
}

/** Returns the part after the last delimiter, or this string if absent. */
public fun String.substringAfterLast(delimiter: Char): String = substringAfterLast(delimiter, this)

/** Returns the part after the last delimiter, or missingDelimiterValue if absent. */
public fun String.substringAfterLast(delimiter: Char, missingDelimiterValue: String): String {
    val index = lastIndexOf(delimiter)
    return if (index < 0) missingDelimiterValue else substring(index + 1, length)
}

/** Returns the part after the last delimiter, or this string if absent. */
public fun String.substringAfterLast(delimiter: String): String = substringAfterLast(delimiter, this)

/** Returns the part after the last delimiter, or missingDelimiterValue if absent. */
public fun String.substringAfterLast(delimiter: String, missingDelimiterValue: String): String {
    val index = lastIndexOf(delimiter)
    return if (index < 0) missingDelimiterValue else substring(index + delimiter.length, length)
}

/** Removes prefix when present, otherwise returns this string. */
public fun String.removePrefix(prefix: String): String =
    if (startsWith(prefix)) substring(prefix.length, length) else this

/** Removes suffix when present, otherwise returns this string. */
public fun String.removeSuffix(suffix: String): String =
    if (endsWith(suffix)) substring(0, length - suffix.length) else this

