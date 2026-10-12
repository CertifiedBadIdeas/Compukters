/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.math

/** Immutable row-major 3x3 matrix multiplying column vectors. */
public value class Matrix3d(
    val m00: Double,
    val m01: Double,
    val m02: Double,
    val m10: Double,
    val m11: Double,
    val m12: Double,
    val m20: Double,
    val m21: Double,
    val m22: Double,
) {
    public operator fun get(row: Int, column: Int): Double {
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

    public fun transposed(): Matrix3d = matrix3dTransposePrimitive(this)

    public operator fun times(other: Matrix3d): Matrix3d = matrix3dMultiplyPrimitive(this, other)

    public operator fun times(vector: Vector3d): Vector3d = matrix3dVectorPrimitive(this, vector)

    public companion object {
        public fun identity(): Matrix3d = Matrix3d(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        public fun scale(value: Vector3d): Matrix3d = Matrix3d(value.x, 0.0, 0.0, 0.0, value.y, 0.0, 0.0, 0.0, value.z)

        public fun scale(value: Double): Matrix3d = scale(Vector3d(value, value, value))

        /** Right-handed rotation around X, in radians. */
        public fun rotationX(angleRadians: Double): Matrix3d {
            val c = cos(angleRadians)
            val s = sin(angleRadians)
            return Matrix3d(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c)
        }

        /** Right-handed rotation around Y, in radians. */
        public fun rotationY(angleRadians: Double): Matrix3d {
            val c = cos(angleRadians)
            val s = sin(angleRadians)
            return Matrix3d(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c)
        }

        /** Right-handed rotation around Z, in radians. */
        public fun rotationZ(angleRadians: Double): Matrix3d {
            val c = cos(angleRadians)
            val s = sin(angleRadians)
            return Matrix3d(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0)
        }
    }
}

private external fun matrix3dTransposePrimitive(matrix: Matrix3d): Matrix3d

private external fun matrix3dMultiplyPrimitive(left: Matrix3d, right: Matrix3d): Matrix3d

private external fun matrix3dVectorPrimitive(matrix: Matrix3d, vector: Vector3d): Vector3d
