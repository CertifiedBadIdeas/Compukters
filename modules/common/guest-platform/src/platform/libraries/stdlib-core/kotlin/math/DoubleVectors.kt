/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.math

/** Immutable 2D Double vector. Arithmetic follows Double semantics. */
public value class Vector2d(
    val x: Double,
    val y: Double,
) {
    public operator fun plus(other: Vector2d): Vector2d = Vector2d(x + other.x, y + other.y)

    public operator fun minus(other: Vector2d): Vector2d = Vector2d(x - other.x, y - other.y)

    public operator fun unaryMinus(): Vector2d = Vector2d(-x, -y)

    public operator fun times(factor: Double): Vector2d = Vector2d(x * factor, y * factor)

    public operator fun div(factor: Double): Vector2d = Vector2d(x / factor, y / factor)

    public infix fun dot(other: Vector2d): Double = x * other.x + y * other.y

    public infix fun cross(other: Vector2d): Double = x * other.y - y * other.x

    public val length: Double get() = hypot(x, y)

    public fun distanceTo(other: Vector2d): Double = hypot(x - other.x, y - other.y)

    /** Unit direction; zero stays zero. Scaling avoids finite magnitude overflow and underflow. */
    public fun normalized(): Vector2d {
        val scale = max(abs(x), abs(y))
        if (scale == 0.0) return Vector2d(0.0, 0.0)
        if (!(scale <= Double.MAX_VALUE)) {
            val magnitude = length
            return Vector2d(x / magnitude, y / magnitude)
        }
        val xScaled = x / scale
        val yScaled = y / scale
        val magnitude = hypot(xScaled, yScaled)
        return Vector2d(xScaled / magnitude, yScaled / magnitude)
    }

    /** Linear interpolation; amounts outside 0..1 extrapolate. */
    public fun lerp(other: Vector2d, amount: Double): Vector2d =
        Vector2d(x + (other.x - x) * amount, y + (other.y - y) * amount)

    public fun toFloat(): Vector2f = Vector2f(x.toFloat(), y.toFloat())

    public fun roundToInt(): Vector2i = Vector2i(x.roundToInt(), y.roundToInt())

    public fun floorToInt(): Vector2i = Vector2i(floorDoubleComponent(x), floorDoubleComponent(y))

    public fun ceilToInt(): Vector2i = Vector2i(ceilDoubleComponent(x), ceilDoubleComponent(y))
}

public operator fun Double.times(vector: Vector2d): Vector2d = vector * this

/** Immutable 3D Double vector. Arithmetic follows Double semantics. */
public value class Vector3d(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    public operator fun plus(other: Vector3d): Vector3d = Vector3d(x + other.x, y + other.y, z + other.z)

    public operator fun minus(other: Vector3d): Vector3d = Vector3d(x - other.x, y - other.y, z - other.z)

    public operator fun unaryMinus(): Vector3d = Vector3d(-x, -y, -z)

    public operator fun times(factor: Double): Vector3d = Vector3d(x * factor, y * factor, z * factor)

    public operator fun div(factor: Double): Vector3d = Vector3d(x / factor, y / factor, z / factor)

    public infix fun dot(other: Vector3d): Double = x * other.x + y * other.y + z * other.z

    public infix fun cross(other: Vector3d): Vector3d =
        Vector3d(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)

    public val length: Double get() = hypot(hypot(x, y), z)

    public fun distanceTo(other: Vector3d): Double = hypot(hypot(x - other.x, y - other.y), z - other.z)

    /** Unit direction; zero stays zero. Scaling avoids finite magnitude overflow and underflow. */
    public fun normalized(): Vector3d {
        val scale = max(max(abs(x), abs(y)), abs(z))
        if (scale == 0.0) return Vector3d(0.0, 0.0, 0.0)
        if (!(scale <= Double.MAX_VALUE)) {
            val magnitude = length
            return Vector3d(x / magnitude, y / magnitude, z / magnitude)
        }
        val xScaled = x / scale
        val yScaled = y / scale
        val zScaled = z / scale
        val magnitude = hypot(hypot(xScaled, yScaled), zScaled)
        return Vector3d(xScaled / magnitude, yScaled / magnitude, zScaled / magnitude)
    }

    /** Linear interpolation; amounts outside 0..1 extrapolate. */
    public fun lerp(other: Vector3d, amount: Double): Vector3d =
        Vector3d(x + (other.x - x) * amount, y + (other.y - y) * amount, z + (other.z - z) * amount)

    public fun toFloat(): Vector3f = Vector3f(x.toFloat(), y.toFloat(), z.toFloat())

    public fun roundToInt(): Vector3i = Vector3i(x.roundToInt(), y.roundToInt(), z.roundToInt())

    public fun floorToInt(): Vector3i = Vector3i(floorDoubleComponent(x), floorDoubleComponent(y), floorDoubleComponent(z))

    public fun ceilToInt(): Vector3i = Vector3i(ceilDoubleComponent(x), ceilDoubleComponent(y), ceilDoubleComponent(z))
}

public operator fun Double.times(vector: Vector3d): Vector3d = vector * this

/** Immutable 4D Double vector. Arithmetic follows Double semantics. */
public value class Vector4d(
    val x: Double,
    val y: Double,
    val z: Double,
    val w: Double,
) {
    public operator fun plus(other: Vector4d): Vector4d = Vector4d(x + other.x, y + other.y, z + other.z, w + other.w)

    public operator fun minus(other: Vector4d): Vector4d = Vector4d(x - other.x, y - other.y, z - other.z, w - other.w)

    public operator fun unaryMinus(): Vector4d = Vector4d(-x, -y, -z, -w)

    public operator fun times(factor: Double): Vector4d = Vector4d(x * factor, y * factor, z * factor, w * factor)

    public operator fun div(factor: Double): Vector4d = Vector4d(x / factor, y / factor, z / factor, w / factor)

    public infix fun dot(other: Vector4d): Double = x * other.x + y * other.y + z * other.z + w * other.w

    public val length: Double get() = hypot(hypot(x, y), hypot(z, w))

    public fun distanceTo(other: Vector4d): Double = hypot(hypot(x - other.x, y - other.y), hypot(z - other.z, w - other.w))

    /** Unit direction; zero stays zero. Scaling avoids finite magnitude overflow and underflow. */
    public fun normalized(): Vector4d {
        val scale = max(max(abs(x), abs(y)), max(abs(z), abs(w)))
        if (scale == 0.0) return Vector4d(0.0, 0.0, 0.0, 0.0)
        if (!(scale <= Double.MAX_VALUE)) {
            val magnitude = length
            return Vector4d(x / magnitude, y / magnitude, z / magnitude, w / magnitude)
        }
        val xScaled = x / scale
        val yScaled = y / scale
        val zScaled = z / scale
        val wScaled = w / scale
        val magnitude = hypot(hypot(xScaled, yScaled), hypot(zScaled, wScaled))
        return Vector4d(xScaled / magnitude, yScaled / magnitude, zScaled / magnitude, wScaled / magnitude)
    }

    /** Linear interpolation; amounts outside 0..1 extrapolate. */
    public fun lerp(other: Vector4d, amount: Double): Vector4d =
        Vector4d(x + (other.x - x) * amount, y + (other.y - y) * amount, z + (other.z - z) * amount, w + (other.w - w) * amount)

    public fun toFloat(): Vector4f = Vector4f(x.toFloat(), y.toFloat(), z.toFloat(), w.toFloat())

    public val xyz: Vector3d get() = Vector3d(x, y, z)

    /** Divide xyz by finite, nonzero w; visibility is tested separately. */
    public fun perspectiveDivide(): Vector3d {
        if (!((w >= -Double.MAX_VALUE && w <= Double.MAX_VALUE) && w != 0.0)) {
            throw IllegalArgumentException("Perspective division requires finite nonzero w.")
        }
        return Vector3d(x / w, y / w, z / w)
    }

    /** Inclusive homogeneous clip bounds with finite positive w and depth [-1, 1]. */
    public val isInsideClipVolume: Boolean
        get() = (w >= -Double.MAX_VALUE && w <= Double.MAX_VALUE) && w > 0.0 && x >= -w && x <= w && y >= -w && y <= w && z >= -w && z <= w
}

public operator fun Double.times(vector: Vector4d): Vector4d = vector * this

private fun floorDoubleComponent(value: Double): Int {
    if (!(value == value)) throw IllegalArgumentException("Cannot round NaN value.")
    return floor(value).toInt()
}

private fun ceilDoubleComponent(value: Double): Int {
    if (!(value == value)) throw IllegalArgumentException("Cannot round NaN value.")
    return ceil(value).toInt()
}
