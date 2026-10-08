/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.math

/** The ratio of a circle's circumference to its diameter. */
public const val PI: Double = 3.141592653589793

/** The base of the natural logarithm. */
public const val E: Double = 2.718281828459045

public fun sin(x: Double): Double = sinPrimitive(x)

public fun cos(x: Double): Double = cosPrimitive(x)

public fun tan(x: Double): Double = tanPrimitive(x)

public fun asin(x: Double): Double = asinPrimitive(x)

public fun acos(x: Double): Double = acosPrimitive(x)

public fun atan(x: Double): Double = atanPrimitive(x)

public fun sinh(x: Double): Double = sinhPrimitive(x)

public fun cosh(x: Double): Double = coshPrimitive(x)

public fun tanh(x: Double): Double = tanhPrimitive(x)

public fun asinh(x: Double): Double = asinhPrimitive(x)

public fun acosh(x: Double): Double = acoshPrimitive(x)

public fun atanh(x: Double): Double = atanhPrimitive(x)

public fun sqrt(x: Double): Double = sqrtPrimitive(x)

public fun cbrt(x: Double): Double = cbrtPrimitive(x)

public fun exp(x: Double): Double = expPrimitive(x)

public fun expm1(x: Double): Double = expm1Primitive(x)

public fun ln(x: Double): Double = lnPrimitive(x)

public fun log10(x: Double): Double = log10Primitive(x)

public fun log2(x: Double): Double = log2Primitive(x)

public fun ln1p(x: Double): Double = ln1pPrimitive(x)

public fun ceil(x: Double): Double = ceilPrimitive(x)

public fun floor(x: Double): Double = floorPrimitive(x)

public fun truncate(x: Double): Double = truncatePrimitive(x)

/** Rounds to an integral value with ties to even, preserving signed zero and non-finite inputs. */
public fun round(x: Double): Double = roundPrimitive(x)

public fun abs(x: Double): Double = absPrimitive(x)

public fun sign(x: Double): Double = signPrimitive(x)

public fun atan2(y: Double, x: Double): Double = atan2Primitive(y, x)

public fun hypot(x: Double, y: Double): Double = hypotPrimitive(x, y)

public fun min(a: Double, b: Double): Double = minPrimitive(a, b)

public fun max(a: Double, b: Double): Double = maxPrimitive(a, b)

public fun Double.pow(x: Double): Double = powPrimitive(this, x)

/** IEEE remainder using the nearest integer quotient with ties to even; differs from `%`. */
public fun Double.IEEErem(divisor: Double): Double = IEEEremPrimitive(this, divisor)

public fun Double.withSign(sign: Double): Double = withSignPrimitive(this, sign)

public fun Double.nextTowards(to: Double): Double = nextTowardsPrimitive(this, to)

public fun Double.nextUp(): Double = nextUpPrimitive(this)

public fun Double.nextDown(): Double = nextDownPrimitive(this)

/** Distance to the next greater magnitude, including the smallest subnormal for zero. */
public val Double.ulp: Double
    get() = ulpPrimitive(this)

public val Double.absoluteValue: Double
    get() = abs(this)

public val Double.sign: Double
    get() = sign(this)

/** Raises this value to the full Int exponent without narrowing the exponent to Float. */
public fun Double.pow(n: Int): Double = pow(n.toDouble())

public fun Double.withSign(sign: Int): Double = withSign(sign.toDouble())

/** Logarithm in the given base; a non-positive base or a base of one produces NaN. */
public fun log(x: Double, base: Double): Double {
    if (base <= 0 || base == 1.0) return Double.NaN
    return ln(x) / ln(base)
}

/** Rounds to the nearest integer, with ties toward positive infinity; rejects NaN and saturates. */
public fun Double.roundToInt(): Int {
    if (this != this) throw IllegalArgumentException("Cannot round NaN value.")
    val value = this
    val lower = floor(value)
    val rounded = if (value - lower >= 0.5) lower + 1.0 else lower
    return rounded.toInt()
}

/** Rounds to the nearest integer, with ties toward positive infinity; rejects NaN and saturates. */
public fun Double.roundToLong(): Long {
    if (this != this) throw IllegalArgumentException("Cannot round NaN value.")
    val value = this
    val lower = floor(value)
    val rounded = if (value - lower >= 0.5) lower + 1.0 else lower
    return rounded.toLong()
}

public fun sin(x: Float): Float = sinPrimitive(x)

public fun cos(x: Float): Float = cosPrimitive(x)

public fun tan(x: Float): Float = tanPrimitive(x)

public fun asin(x: Float): Float = asinPrimitive(x)

public fun acos(x: Float): Float = acosPrimitive(x)

public fun atan(x: Float): Float = atanPrimitive(x)

public fun sinh(x: Float): Float = sinhPrimitive(x)

public fun cosh(x: Float): Float = coshPrimitive(x)

public fun tanh(x: Float): Float = tanhPrimitive(x)

public fun asinh(x: Float): Float = asinhPrimitive(x)

public fun acosh(x: Float): Float = acoshPrimitive(x)

public fun atanh(x: Float): Float = atanhPrimitive(x)

public fun sqrt(x: Float): Float = sqrtPrimitive(x)

public fun cbrt(x: Float): Float = cbrtPrimitive(x)

public fun exp(x: Float): Float = expPrimitive(x)

public fun expm1(x: Float): Float = expm1Primitive(x)

public fun ln(x: Float): Float = lnPrimitive(x)

public fun log10(x: Float): Float = log10Primitive(x)

public fun log2(x: Float): Float = log2Primitive(x)

public fun ln1p(x: Float): Float = ln1pPrimitive(x)

public fun ceil(x: Float): Float = ceilPrimitive(x)

public fun floor(x: Float): Float = floorPrimitive(x)

public fun truncate(x: Float): Float = truncatePrimitive(x)

/** Rounds to an integral value with ties to even, preserving signed zero and non-finite inputs. */
public fun round(x: Float): Float = roundPrimitive(x)

public fun abs(x: Float): Float = absPrimitive(x)

public fun sign(x: Float): Float = signPrimitive(x)

public fun atan2(y: Float, x: Float): Float = atan2Primitive(y, x)

public fun hypot(x: Float, y: Float): Float = hypotPrimitive(x, y)

public fun min(a: Float, b: Float): Float = minPrimitive(a, b)

public fun max(a: Float, b: Float): Float = maxPrimitive(a, b)

public fun Float.pow(x: Float): Float = powPrimitive(this, x)

/** IEEE remainder using the nearest integer quotient with ties to even; differs from `%`. */
public fun Float.IEEErem(divisor: Float): Float = IEEEremPrimitive(this, divisor)

public fun Float.withSign(sign: Float): Float = withSignPrimitive(this, sign)

public fun Float.nextTowards(to: Float): Float = nextTowardsPrimitive(this, to)

public fun Float.nextUp(): Float = nextUpPrimitive(this)

public fun Float.nextDown(): Float = nextDownPrimitive(this)

/** Distance to the next greater magnitude, including the smallest subnormal for zero. */
public val Float.ulp: Float
    get() = ulpPrimitive(this)

public val Float.absoluteValue: Float
    get() = abs(this)

public val Float.sign: Float
    get() = sign(this)

/** Raises this value to the full Int exponent without narrowing the exponent to Float. */
public fun Float.pow(n: Int): Float = toDouble().pow(n.toDouble()).toFloat()

public fun Float.withSign(sign: Int): Float = withSign(sign.toFloat())

/** Logarithm in the given base; a non-positive base or a base of one produces NaN. */
public fun log(x: Float, base: Float): Float {
    if (base <= 0 || base == 1.0f) return Float.NaN
    return ln(x) / ln(base)
}

/** Rounds to the nearest integer, with ties toward positive infinity; rejects NaN and saturates. */
public fun Float.roundToInt(): Int {
    if (this != this) throw IllegalArgumentException("Cannot round NaN value.")
    val value = toDouble()
    val lower = floor(value)
    val rounded = if (value - lower >= 0.5) lower + 1.0 else lower
    return rounded.toInt()
}

/** Rounds to the nearest integer, with ties toward positive infinity; rejects NaN and saturates. */
public fun Float.roundToLong(): Long {
    if (this != this) throw IllegalArgumentException("Cannot round NaN value.")
    val value = toDouble()
    val lower = floor(value)
    val rounded = if (value - lower >= 0.5) lower + 1.0 else lower
    return rounded.toLong()
}
