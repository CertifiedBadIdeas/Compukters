/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.math

/** Immutable row-major 3x3 matrix multiplying column vectors. */
public value class Matrix3f(
    val m00: Float,
    val m01: Float,
    val m02: Float,
    val m10: Float,
    val m11: Float,
    val m12: Float,
    val m20: Float,
    val m21: Float,
    val m22: Float,
) {
    public operator fun get(row: Int, column: Int): Float {
        if (row < 0 || row > 2 || column < 0 || column > 2) throw IndexOutOfBoundsException("Matrix index outside 0..2.")
        return when (row * 3 + column) {
            0 -> m00
            1 -> m01
            2 -> m02
            3 -> m10
            4 -> m11
            5 -> m12
            6 -> m20
            7 -> m21
            else -> m22
        }
    }

    public fun transposed(): Matrix3f = matrix3fTransposePrimitive(this)

    public operator fun times(other: Matrix3f): Matrix3f = matrix3fMultiplyPrimitive(this, other)

    public operator fun times(vector: Vector3f): Vector3f = matrix3fVectorPrimitive(this, vector)

    public companion object {
        public fun identity(): Matrix3f = Matrix3f(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

        public fun scale(value: Vector3f): Matrix3f = Matrix3f(value.x, 0f, 0f, 0f, value.y, 0f, 0f, 0f, value.z)

        public fun scale(value: Float): Matrix3f = scale(Vector3f(value, value, value))

        /** Right-handed rotation around X, in radians. */
        public fun rotationX(angleRadians: Float): Matrix3f {
            val c = cos(angleRadians)
            val s = sin(angleRadians)
            return Matrix3f(1f, 0f, 0f, 0f, c, -s, 0f, s, c)
        }

        /** Right-handed rotation around Y, in radians. */
        public fun rotationY(angleRadians: Float): Matrix3f {
            val c = cos(angleRadians)
            val s = sin(angleRadians)
            return Matrix3f(c, 0f, s, 0f, 1f, 0f, -s, 0f, c)
        }

        /** Right-handed rotation around Z, in radians. */
        public fun rotationZ(angleRadians: Float): Matrix3f {
            val c = cos(angleRadians)
            val s = sin(angleRadians)
            return Matrix3f(c, -s, 0f, s, c, 0f, 0f, 0f, 1f)
        }
    }
}

private external fun matrix3fTransposePrimitive(matrix: Matrix3f): Matrix3f

private external fun matrix3fMultiplyPrimitive(left: Matrix3f, right: Matrix3f): Matrix3f

private external fun matrix3fVectorPrimitive(matrix: Matrix3f, vector: Vector3f): Vector3f
