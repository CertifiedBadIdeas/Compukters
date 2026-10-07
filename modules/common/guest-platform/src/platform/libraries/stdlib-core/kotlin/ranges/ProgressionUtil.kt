/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.ranges

internal fun positiveModulo(value: Long, modulus: Long): Long {
    val remainder = value % modulus
    return if (remainder < 0L) remainder + modulus else remainder
}

internal fun differenceModulo(left: Long, right: Long, modulus: Long): Long =
    positiveModulo(positiveModulo(left, modulus) - positiveModulo(right, modulus), modulus)

internal fun signedProgressionLast(first: Long, last: Long, step: Long): Long {
    require(step != 0L && step != Long.MIN_VALUE)
    return if (step > 0L) {
        if (first >= last) last else last - differenceModulo(last, first, step)
    } else {
        if (first <= last) last else last + differenceModulo(first, last, -step)
    }
}
