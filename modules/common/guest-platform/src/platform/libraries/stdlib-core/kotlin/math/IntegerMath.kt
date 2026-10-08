/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.math

/** The minimum value retains its negative value because its magnitude is not representable. */
public fun abs(n: Int): Int = if (n < 0) -n else n

public fun min(a: Int, b: Int): Int = if (a <= b) a else b

public fun max(a: Int, b: Int): Int = if (a >= b) a else b

public val Int.absoluteValue: Int
    get() = abs(this)

public val Int.sign: Int
    get() = if (this < 0) -1 else if (this > 0) 1 else 0

/** The minimum value retains its negative value because its magnitude is not representable. */
public fun abs(n: Long): Long = if (n < 0L) -n else n

public fun min(a: Long, b: Long): Long = if (a <= b) a else b

public fun max(a: Long, b: Long): Long = if (a >= b) a else b

public val Long.absoluteValue: Long
    get() = abs(this)

public val Long.sign: Int
    get() = if (this < 0L) -1 else if (this > 0L) 1 else 0
