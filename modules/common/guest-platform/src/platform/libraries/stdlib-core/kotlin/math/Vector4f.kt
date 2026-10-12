/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.math

/** Immutable homogeneous Float vector. Projection division is explicit; arithmetic follows Float semantics. */
public value class Vector4f(
    val x: Float,
    val y: Float,
    val z: Float,
    val w: Float,
) {
    public operator fun plus(other: Vector4f): Vector4f = Vector4f(x + other.x, y + other.y, z + other.z, w + other.w)

    public operator fun minus(other: Vector4f): Vector4f = Vector4f(x - other.x, y - other.y, z - other.z, w - other.w)

    public operator fun unaryMinus(): Vector4f = Vector4f(-x, -y, -z, -w)

    public operator fun times(factor: Float): Vector4f = Vector4f(x * factor, y * factor, z * factor, w * factor)

    public operator fun div(factor: Float): Vector4f = Vector4f(x / factor, y / factor, z / factor, w / factor)

    public infix fun dot(other: Vector4f): Float = x * other.x + y * other.y + z * other.z + w * other.w

    public fun toDouble(): Vector4d = Vector4d(x.toDouble(), y.toDouble(), z.toDouble(), w.toDouble())

    public val xyz: Vector3f get() = Vector3f(x, y, z)

    public val length: Float get() = hypot(hypot(x.toDouble(), y.toDouble()), hypot(z.toDouble(), w.toDouble())).toFloat()

    /** Unit vector; zero stays zero. Double intermediates avoid Float magnitude overflow. */
    public fun normalized(): Vector4f {
        val magnitude = hypot(hypot(x.toDouble(), y.toDouble()), hypot(z.toDouble(), w.toDouble()))
        if (magnitude == 0.0) return Vector4f(0f, 0f, 0f, 0f)
        return Vector4f(
            (x.toDouble() / magnitude).toFloat(),
            (y.toDouble() / magnitude).toFloat(),
            (z.toDouble() / magnitude).toFloat(),
            (w.toDouble() / magnitude).toFloat(),
        )
    }

    /** Divide xyz by finite, nonzero w. Does not clip or reject points behind the camera. */
    public fun perspectiveDivide(): Vector3f {
        if (!((w >= -Float.MAX_VALUE && w <= Float.MAX_VALUE) && w != 0f)) {
            throw IllegalArgumentException(
                "Perspective division requires finite nonzero w.",
            )
        }
        return Vector3f(x / w, y / w, z / w)
    }

    /** Inclusive homogeneous clip bounds, with positive finite w and normalized depth [-1, 1]. */
    public val isInsideClipVolume: Boolean
        get() = (w >= -Float.MAX_VALUE && w <= Float.MAX_VALUE) && w > 0f && x >= -w && x <= w && y >= -w && y <= w && z >= -w && z <= w
}

public operator fun Float.times(vector: Vector4f): Vector4f = vector * this
