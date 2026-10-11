/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.math

/** Immutable 2D Int vector. Arithmetic follows the scalar type, including overflow and non-finite values. */
public value class Vector2i(val x: Int, val y: Int) {
    public operator fun plus(other: Vector2i): Vector2i = Vector2i(x + other.x, y + other.y)
    public operator fun minus(other: Vector2i): Vector2i = Vector2i(x - other.x, y - other.y)
    public operator fun unaryMinus(): Vector2i = Vector2i(-x, -y)
    public operator fun times(factor: Int): Vector2i = Vector2i(x * factor, y * factor)
    public operator fun div(factor: Int): Vector2i = Vector2i(x / factor, y / factor)
    public infix fun dot(other: Vector2i): Int = x * other.x + y * other.y
    public infix fun cross(other: Vector2i): Int = x * other.y - y * other.x
    public val length: Double get() = hypot(x.toDouble(), y.toDouble())
    public fun distanceTo(other: Vector2i): Double = hypot(x.toDouble() - other.x.toDouble(), y.toDouble() - other.y.toDouble())
    public fun toFloat(): Vector2f = Vector2f(x.toFloat(), y.toFloat())
}

public operator fun Int.times(vector: Vector2i): Vector2i = vector * this

/** Immutable 2D Float vector. Arithmetic follows the scalar type, including overflow and non-finite values. */
public value class Vector2f(val x: Float, val y: Float) {
    public operator fun plus(other: Vector2f): Vector2f = Vector2f(x + other.x, y + other.y)
    public operator fun minus(other: Vector2f): Vector2f = Vector2f(x - other.x, y - other.y)
    public operator fun unaryMinus(): Vector2f = Vector2f(-x, -y)
    public operator fun times(factor: Float): Vector2f = Vector2f(x * factor, y * factor)
    public operator fun div(factor: Float): Vector2f = Vector2f(x / factor, y / factor)
    public infix fun dot(other: Vector2f): Float = x * other.x + y * other.y
    public infix fun cross(other: Vector2f): Float = x * other.y - y * other.x
    public val length: Float get() = hypot(x.toDouble(), y.toDouble()).toFloat()
    public fun distanceTo(other: Vector2f): Float = hypot(x.toDouble() - other.x.toDouble(), y.toDouble() - other.y.toDouble()).toFloat()

    /** Unit direction; a zero vector stays zero. Double intermediates avoid Float magnitude overflow. */
    public fun normalized(): Vector2f {
        val magnitude = hypot(x.toDouble(), y.toDouble())
        if (magnitude == 0.0) return Vector2f(0f, 0f)
        return Vector2f((x.toDouble() / magnitude).toFloat(), (y.toDouble() / magnitude).toFloat())
    }

    /** Linear interpolation; amounts outside 0..1 extrapolate. */
    public fun lerp(other: Vector2f, amount: Float): Vector2f = Vector2f((x.toDouble() + (other.x.toDouble() - x.toDouble()) * amount.toDouble()).toFloat(), (y.toDouble() + (other.y.toDouble() - y.toDouble()) * amount.toDouble()).toFloat())
    public fun roundToInt(): Vector2i = Vector2i(x.roundToInt(), y.roundToInt())
    public fun floorToInt(): Vector2i = Vector2i(floorComponent(x), floorComponent(y))
    public fun ceilToInt(): Vector2i = Vector2i(ceilComponent(x), ceilComponent(y))
}

public operator fun Float.times(vector: Vector2f): Vector2f = vector * this

/** Immutable 3D Int vector. Arithmetic follows the scalar type, including overflow and non-finite values. */
public value class Vector3i(val x: Int, val y: Int, val z: Int) {
    public operator fun plus(other: Vector3i): Vector3i = Vector3i(x + other.x, y + other.y, z + other.z)
    public operator fun minus(other: Vector3i): Vector3i = Vector3i(x - other.x, y - other.y, z - other.z)
    public operator fun unaryMinus(): Vector3i = Vector3i(-x, -y, -z)
    public operator fun times(factor: Int): Vector3i = Vector3i(x * factor, y * factor, z * factor)
    public operator fun div(factor: Int): Vector3i = Vector3i(x / factor, y / factor, z / factor)
    public infix fun dot(other: Vector3i): Int = x * other.x + y * other.y + z * other.z
    public infix fun cross(other: Vector3i): Vector3i = Vector3i(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)
    public val length: Double get() = hypot(hypot(x.toDouble(), y.toDouble()), z.toDouble())
    public fun distanceTo(other: Vector3i): Double = hypot(hypot(x.toDouble() - other.x.toDouble(), y.toDouble() - other.y.toDouble()), z.toDouble() - other.z.toDouble())
    public fun toFloat(): Vector3f = Vector3f(x.toFloat(), y.toFloat(), z.toFloat())
}

public operator fun Int.times(vector: Vector3i): Vector3i = vector * this

/** Immutable 3D Float vector. Arithmetic follows the scalar type, including overflow and non-finite values. */
public value class Vector3f(val x: Float, val y: Float, val z: Float) {
    public operator fun plus(other: Vector3f): Vector3f = Vector3f(x + other.x, y + other.y, z + other.z)
    public operator fun minus(other: Vector3f): Vector3f = Vector3f(x - other.x, y - other.y, z - other.z)
    public operator fun unaryMinus(): Vector3f = Vector3f(-x, -y, -z)
    public operator fun times(factor: Float): Vector3f = Vector3f(x * factor, y * factor, z * factor)
    public operator fun div(factor: Float): Vector3f = Vector3f(x / factor, y / factor, z / factor)
    public infix fun dot(other: Vector3f): Float = x * other.x + y * other.y + z * other.z
    public infix fun cross(other: Vector3f): Vector3f = Vector3f(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)
    public val length: Float get() = hypot(hypot(x.toDouble(), y.toDouble()), z.toDouble()).toFloat()
    public fun distanceTo(other: Vector3f): Float = hypot(hypot(x.toDouble() - other.x.toDouble(), y.toDouble() - other.y.toDouble()), z.toDouble() - other.z.toDouble()).toFloat()

    /** Unit direction; a zero vector stays zero. Double intermediates avoid Float magnitude overflow. */
    public fun normalized(): Vector3f {
        val magnitude = hypot(hypot(x.toDouble(), y.toDouble()), z.toDouble())
        if (magnitude == 0.0) return Vector3f(0f, 0f, 0f)
        return Vector3f((x.toDouble() / magnitude).toFloat(), (y.toDouble() / magnitude).toFloat(), (z.toDouble() / magnitude).toFloat())
    }

    /** Linear interpolation; amounts outside 0..1 extrapolate. */
    public fun lerp(other: Vector3f, amount: Float): Vector3f = Vector3f((x.toDouble() + (other.x.toDouble() - x.toDouble()) * amount.toDouble()).toFloat(), (y.toDouble() + (other.y.toDouble() - y.toDouble()) * amount.toDouble()).toFloat(), (z.toDouble() + (other.z.toDouble() - z.toDouble()) * amount.toDouble()).toFloat())
    public fun roundToInt(): Vector3i = Vector3i(x.roundToInt(), y.roundToInt(), z.roundToInt())
    public fun floorToInt(): Vector3i = Vector3i(floorComponent(x), floorComponent(y), floorComponent(z))
    public fun ceilToInt(): Vector3i = Vector3i(ceilComponent(x), ceilComponent(y), ceilComponent(z))
}

public operator fun Float.times(vector: Vector3f): Vector3f = vector * this

private fun floorComponent(value: Float): Int {
    if (!(value == value)) throw IllegalArgumentException("Cannot round NaN value.")
    return floor(value).toInt()
}

private fun ceilComponent(value: Float): Int {
    if (!(value == value)) throw IllegalArgumentException("Cannot round NaN value.")
    return ceil(value).toInt()
}
