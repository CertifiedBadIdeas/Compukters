/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.math

/**
 * Immutable 4x4 matrix with row/column-named components, in row-major constructor order.
 * Multiplies column vectors: (a * b) * point applies b first. Use identity() for the identity matrix.
 */
public value class Matrix4f(
    val m00: Float,
    val m01: Float,
    val m02: Float,
    val m03: Float,
    val m10: Float,
    val m11: Float,
    val m12: Float,
    val m13: Float,
    val m20: Float,
    val m21: Float,
    val m22: Float,
    val m23: Float,
    val m30: Float,
    val m31: Float,
    val m32: Float,
    val m33: Float,
) {
    public operator fun get(
        row: Int,
        column: Int,
    ): Float {
        if (row < 0 || row > 3 || column < 0 || column > 3) throw IndexOutOfBoundsException("Matrix index outside 0..3.")
        return when (row * 4 + column) {
            0 -> m00
            1 -> m01
            2 -> m02
            3 -> m03
            4 -> m10
            5 -> m11
            6 -> m12
            7 -> m13
            8 -> m20
            9 -> m21
            10 -> m22
            11 -> m23
            12 -> m30
            13 -> m31
            14 -> m32
            else -> m33
        }
    }

    public fun transposed(): Matrix4f =
        Matrix4f(
            m00,
            m10,
            m20,
            m30,
            m01,
            m11,
            m21,
            m31,
            m02,
            m12,
            m22,
            m32,
            m03,
            m13,
            m23,
            m33,
        )

    public operator fun times(other: Matrix4f): Matrix4f =
        Matrix4f(
            m00 * other.m00 + m01 * other.m10 + m02 * other.m20 + m03 * other.m30,
            m00 * other.m01 + m01 * other.m11 + m02 * other.m21 + m03 * other.m31,
            m00 * other.m02 + m01 * other.m12 + m02 * other.m22 + m03 * other.m32,
            m00 * other.m03 + m01 * other.m13 + m02 * other.m23 + m03 * other.m33,
            m10 * other.m00 + m11 * other.m10 + m12 * other.m20 + m13 * other.m30,
            m10 * other.m01 + m11 * other.m11 + m12 * other.m21 + m13 * other.m31,
            m10 * other.m02 + m11 * other.m12 + m12 * other.m22 + m13 * other.m32,
            m10 * other.m03 + m11 * other.m13 + m12 * other.m23 + m13 * other.m33,
            m20 * other.m00 + m21 * other.m10 + m22 * other.m20 + m23 * other.m30,
            m20 * other.m01 + m21 * other.m11 + m22 * other.m21 + m23 * other.m31,
            m20 * other.m02 + m21 * other.m12 + m22 * other.m22 + m23 * other.m32,
            m20 * other.m03 + m21 * other.m13 + m22 * other.m23 + m23 * other.m33,
            m30 * other.m00 + m31 * other.m10 + m32 * other.m20 + m33 * other.m30,
            m30 * other.m01 + m31 * other.m11 + m32 * other.m21 + m33 * other.m31,
            m30 * other.m02 + m31 * other.m12 + m32 * other.m22 + m33 * other.m32,
            m30 * other.m03 + m31 * other.m13 + m32 * other.m23 + m33 * other.m33,
        )

    public operator fun times(vector: Vector4f): Vector4f =
        Vector4f(
            m00 * vector.x + m01 * vector.y + m02 * vector.z + m03 * vector.w,
            m10 * vector.x + m11 * vector.y + m12 * vector.z + m13 * vector.w,
            m20 * vector.x + m21 * vector.y + m22 * vector.z + m23 * vector.w,
            m30 * vector.x + m31 * vector.y + m32 * vector.z + m33 * vector.w,
        )

    /** Lift a point with w=1, retaining the result's homogeneous w. */
    public operator fun times(point: Vector3f): Vector4f = transformPoint(point)

    public fun transformPoint(point: Vector3f): Vector4f = this * Vector4f(point.x, point.y, point.z, 1f)

    /** Lift a direction with w=0, so affine translation has no effect. */
    public fun transformDirection(direction: Vector3f): Vector4f = this * Vector4f(direction.x, direction.y, direction.z, 0f)

    public companion object {
        public fun identity(): Matrix4f =
            Matrix4f(
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
            )

        public fun translation(offset: Vector3f): Matrix4f =
            Matrix4f(
                1f,
                0f,
                0f,
                offset.x,
                0f,
                1f,
                0f,
                offset.y,
                0f,
                0f,
                1f,
                offset.z,
                0f,
                0f,
                0f,
                1f,
            )

        public fun scale(factors: Vector3f): Matrix4f =
            Matrix4f(
                factors.x,
                0f,
                0f,
                0f,
                0f,
                factors.y,
                0f,
                0f,
                0f,
                0f,
                factors.z,
                0f,
                0f,
                0f,
                0f,
                1f,
            )

        public fun scale(factor: Float): Matrix4f = scale(Vector3f(factor, factor, factor))

        /** Right-handed rotation around X, in radians. */
        public fun rotationX(angleRadians: Float): Matrix4f {
            val c = cos(angleRadians)
            val s = sin(angleRadians)
            return Matrix4f(
                1f,
                0f,
                0f,
                0f,
                0f,
                c,
                -s,
                0f,
                0f,
                s,
                c,
                0f,
                0f,
                0f,
                0f,
                1f,
            )
        }

        /** Right-handed rotation around Y, in radians. */
        public fun rotationY(angleRadians: Float): Matrix4f {
            val c = cos(angleRadians)
            val s = sin(angleRadians)
            return Matrix4f(
                c,
                0f,
                s,
                0f,
                0f,
                1f,
                0f,
                0f,
                -s,
                0f,
                c,
                0f,
                0f,
                0f,
                0f,
                1f,
            )
        }

        /** Right-handed rotation around Z, in radians. */
        public fun rotationZ(angleRadians: Float): Matrix4f {
            val c = cos(angleRadians)
            val s = sin(angleRadians)
            return Matrix4f(
                c,
                -s,
                0f,
                0f,
                s,
                c,
                0f,
                0f,
                0f,
                0f,
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
            )
        }

        /**
         * Right-handed perspective, looking down negative Z, with normalized depth [-1, 1].
         * Requires finite 0 < field of view < PI, aspect > 0 and 0 < near < far.
         */
        public fun perspective(
            verticalFieldOfViewRadians: Float,
            aspectRatio: Float,
            near: Float,
            far: Float,
        ): Matrix4f {
            if (!(
                    finiteMatrixComponent(verticalFieldOfViewRadians) && verticalFieldOfViewRadians > 0f &&
                        verticalFieldOfViewRadians.toDouble() < PI
                )
            ) {
                throw IllegalArgumentException("Perspective field of view must be between zero and PI radians.")
            }
            if (!(
                    finiteMatrixComponent(aspectRatio) && aspectRatio > 0f && finiteMatrixComponent(near) && finiteMatrixComponent(far) &&
                        near > 0f &&
                        far > near
                )
            ) {
                throw IllegalArgumentException("Perspective requires positive aspect and 0 < near < far.")
            }
            val f = 1.0 / tan(verticalFieldOfViewRadians.toDouble() / 2.0)
            val depth = near.toDouble() - far.toDouble()
            return Matrix4f(
                projectionComponent(f / aspectRatio.toDouble()),
                0f,
                0f,
                0f,
                0f,
                projectionComponent(f),
                0f,
                0f,
                0f,
                0f,
                projectionComponent((far.toDouble() + near.toDouble()) / depth),
                projectionComponent(2.0 * far.toDouble() * near.toDouble() / depth),
                0f,
                0f,
                -1f,
                0f,
            )
        }

        /** Right-handed orthographic projection with normalized depth [-1, 1] and finite ordered bounds. */
        public fun orthographic(
            left: Float,
            right: Float,
            bottom: Float,
            top: Float,
            near: Float,
            far: Float,
        ): Matrix4f {
            if (!(
                    finiteMatrixComponent(
                        left,
                    ) && finiteMatrixComponent(right) && finiteMatrixComponent(bottom) && finiteMatrixComponent(top) &&
                        finiteMatrixComponent(near) &&
                        finiteMatrixComponent(far)
                )
            ) {
                throw IllegalArgumentException("Orthographic bounds must be finite.")
            }
            if (!(left < right && bottom < top && near < far)) throw IllegalArgumentException("Orthographic bounds must be ordered.")
            val width = right.toDouble() - left.toDouble()
            val height = top.toDouble() - bottom.toDouble()
            val depth = far.toDouble() - near.toDouble()
            return Matrix4f(
                projectionComponent(2.0 / width),
                0f,
                0f,
                projectionComponent(-(right.toDouble() + left.toDouble()) / width),
                0f,
                projectionComponent(2.0 / height),
                0f,
                projectionComponent(-(top.toDouble() + bottom.toDouble()) / height),
                0f,
                0f,
                projectionComponent(-2.0 / depth),
                projectionComponent(-(far.toDouble() + near.toDouble()) / depth),
                0f,
                0f,
                0f,
                1f,
            )
        }
    }
}

private fun projectionComponent(value: Double): Float {
    val component = value.toFloat()
    if (!(finiteMatrixComponent(component))) throw IllegalArgumentException("Projection coefficient does not fit in Float.")
    return component
}

private fun finiteMatrixComponent(value: Float): Boolean = value >= -Float.MAX_VALUE && value <= Float.MAX_VALUE
