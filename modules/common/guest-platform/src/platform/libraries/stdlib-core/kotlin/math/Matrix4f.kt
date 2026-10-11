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

    public fun transposed(): Matrix4f = matrixTransposePrimitive(this)

    public operator fun times(other: Matrix4f): Matrix4f = matrixMultiplyPrimitive(this, other)

    public operator fun times(vector: Vector4f): Vector4f = matrixVectorPrimitive(this, vector)

    /** Lift a point with w=1, retaining the result's homogeneous w. */
    public operator fun times(point: Vector3f): Vector4f = transformPoint(point)

    public fun transformPoint(point: Vector3f): Vector4f = matrixPointPrimitive(this, point)

    /** Lift a direction with w=0, so affine translation has no effect. */
    public fun transformDirection(direction: Vector3f): Vector4f = matrixDirectionPrimitive(this, direction)

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

private external fun matrixTransposePrimitive(matrix: Matrix4f): Matrix4f

private external fun matrixMultiplyPrimitive(
    left: Matrix4f,
    right: Matrix4f,
): Matrix4f

private external fun matrixVectorPrimitive(
    matrix: Matrix4f,
    vector: Vector4f,
): Vector4f

private external fun matrixPointPrimitive(
    matrix: Matrix4f,
    point: Vector3f,
): Vector4f

private external fun matrixDirectionPrimitive(
    matrix: Matrix4f,
    direction: Vector3f,
): Vector4f
