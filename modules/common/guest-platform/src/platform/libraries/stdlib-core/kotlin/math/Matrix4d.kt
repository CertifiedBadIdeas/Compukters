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
public value class Matrix4d(
    val m00: Double,
    val m01: Double,
    val m02: Double,
    val m03: Double,
    val m10: Double,
    val m11: Double,
    val m12: Double,
    val m13: Double,
    val m20: Double,
    val m21: Double,
    val m22: Double,
    val m23: Double,
    val m30: Double,
    val m31: Double,
    val m32: Double,
    val m33: Double,
) {
    public operator fun get(
        row: Int,
        column: Int,
    ): Double {
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

    public fun transposed(): Matrix4d = matrix4dTransposePrimitive(this)

    public operator fun times(other: Matrix4d): Matrix4d = matrix4dMultiplyPrimitive(this, other)

    public operator fun times(vector: Vector4d): Vector4d = matrix4dVectorPrimitive(this, vector)

    /** Lift a point with w=1, retaining the result's homogeneous w. */
    public operator fun times(point: Vector3d): Vector4d = transformPoint(point)

    public fun transformPoint(point: Vector3d): Vector4d = matrix4dPointPrimitive(this, point)

    /** Lift a direction with w=0, so affine translation has no effect. */
    public fun transformDirection(direction: Vector3d): Vector4d = matrix4dDirectionPrimitive(this, direction)

    public companion object {
        public fun identity(): Matrix4d =
            Matrix4d(
                1.0,
                0.0,
                0.0,
                0.0,
                0.0,
                1.0,
                0.0,
                0.0,
                0.0,
                0.0,
                1.0,
                0.0,
                0.0,
                0.0,
                0.0,
                1.0,
            )

        public fun translation(offset: Vector3d): Matrix4d =
            Matrix4d(
                1.0,
                0.0,
                0.0,
                offset.x,
                0.0,
                1.0,
                0.0,
                offset.y,
                0.0,
                0.0,
                1.0,
                offset.z,
                0.0,
                0.0,
                0.0,
                1.0,
            )

        public fun scale(factors: Vector3d): Matrix4d =
            Matrix4d(
                factors.x,
                0.0,
                0.0,
                0.0,
                0.0,
                factors.y,
                0.0,
                0.0,
                0.0,
                0.0,
                factors.z,
                0.0,
                0.0,
                0.0,
                0.0,
                1.0,
            )

        public fun scale(factor: Double): Matrix4d = scale(Vector3d(factor, factor, factor))

        /** Right-handed rotation around X, in radians. */
        public fun rotationX(angleRadians: Double): Matrix4d {
            val c = cos(angleRadians)
            val s = sin(angleRadians)
            return Matrix4d(
                1.0,
                0.0,
                0.0,
                0.0,
                0.0,
                c,
                -s,
                0.0,
                0.0,
                s,
                c,
                0.0,
                0.0,
                0.0,
                0.0,
                1.0,
            )
        }

        /** Right-handed rotation around Y, in radians. */
        public fun rotationY(angleRadians: Double): Matrix4d {
            val c = cos(angleRadians)
            val s = sin(angleRadians)
            return Matrix4d(
                c,
                0.0,
                s,
                0.0,
                0.0,
                1.0,
                0.0,
                0.0,
                -s,
                0.0,
                c,
                0.0,
                0.0,
                0.0,
                0.0,
                1.0,
            )
        }

        /** Right-handed rotation around Z, in radians. */
        public fun rotationZ(angleRadians: Double): Matrix4d {
            val c = cos(angleRadians)
            val s = sin(angleRadians)
            return Matrix4d(
                c,
                -s,
                0.0,
                0.0,
                s,
                c,
                0.0,
                0.0,
                0.0,
                0.0,
                1.0,
                0.0,
                0.0,
                0.0,
                0.0,
                1.0,
            )
        }

        /**
         * Right-handed perspective, looking down negative Z, with normalized depth [-1, 1].
         * Requires finite 0 < field of view < PI, aspect > 0 and 0 < near < far.
         */
        public fun perspective(
            verticalFieldOfViewRadians: Double,
            aspectRatio: Double,
            near: Double,
            far: Double,
        ): Matrix4d {
            if (!(
                    finiteDoubleMatrixComponent(verticalFieldOfViewRadians) && verticalFieldOfViewRadians > 0.0 &&
                        verticalFieldOfViewRadians < PI
                )
            ) {
                throw IllegalArgumentException("Perspective field of view must be between zero and PI radians.")
            }
            if (!(
                    finiteDoubleMatrixComponent(aspectRatio) && aspectRatio > 0.0 && finiteDoubleMatrixComponent(near) && finiteDoubleMatrixComponent(far) &&
                        near > 0.0 &&
                        far > near
                )
            ) {
                throw IllegalArgumentException("Perspective requires positive aspect and 0 < near < far.")
            }
            val f = 1.0 / tan(verticalFieldOfViewRadians / 2.0)
            val depth = near - far
            return Matrix4d(
                projectionDoubleComponent(f / aspectRatio),
                0.0,
                0.0,
                0.0,
                0.0,
                projectionDoubleComponent(f),
                0.0,
                0.0,
                0.0,
                0.0,
                projectionDoubleComponent(far / depth + near / depth),
                projectionDoubleComponent(2.0 * (far / depth * near)),
                0.0,
                0.0,
                -1.0,
                0.0,
            )
        }

        /** Right-handed orthographic projection with normalized depth [-1, 1] and finite ordered bounds. */
        public fun orthographic(
            left: Double,
            right: Double,
            bottom: Double,
            top: Double,
            near: Double,
            far: Double,
        ): Matrix4d {
            if (!(
                    finiteDoubleMatrixComponent(
                        left,
                    ) && finiteDoubleMatrixComponent(right) && finiteDoubleMatrixComponent(bottom) && finiteDoubleMatrixComponent(top) &&
                        finiteDoubleMatrixComponent(near) &&
                        finiteDoubleMatrixComponent(far)
                )
            ) {
                throw IllegalArgumentException("Orthographic bounds must be finite.")
            }
            if (!(left < right && bottom < top && near < far)) throw IllegalArgumentException("Orthographic bounds must be ordered.")
            return Matrix4d(
                projectionDoubleComponent(projectionDoubleScale(left, right)),
                0.0,
                0.0,
                projectionDoubleComponent(projectionDoubleOffset(left, right)),
                0.0,
                projectionDoubleComponent(projectionDoubleScale(bottom, top)),
                0.0,
                projectionDoubleComponent(projectionDoubleOffset(bottom, top)),
                0.0,
                0.0,
                projectionDoubleComponent(-projectionDoubleScale(near, far)),
                projectionDoubleComponent(projectionDoubleOffset(near, far)),
                0.0,
                0.0,
                0.0,
                1.0,
            )
        }
    }
}

private fun projectionDoubleScale(low: Double, high: Double): Double {
    val span = high - low
    return if (span <= Double.MAX_VALUE) 2.0 / span else 1.0 / (high / 2.0 - low / 2.0)
}

private fun projectionDoubleOffset(low: Double, high: Double): Double {
    val span = high - low
    return if (span <= Double.MAX_VALUE) -(high / span + low / span) else -(high / 2.0 + low / 2.0) / (high / 2.0 - low / 2.0)
}

private fun projectionDoubleComponent(value: Double): Double {
    val component = value
    if (!(finiteDoubleMatrixComponent(component))) throw IllegalArgumentException("Projection coefficient does not fit in Double.")
    return component
}

private fun finiteDoubleMatrixComponent(value: Double): Boolean = value >= -Double.MAX_VALUE && value <= Double.MAX_VALUE

private external fun matrix4dTransposePrimitive(matrix: Matrix4d): Matrix4d

private external fun matrix4dMultiplyPrimitive(
    left: Matrix4d,
    right: Matrix4d,
): Matrix4d

private external fun matrix4dVectorPrimitive(
    matrix: Matrix4d,
    vector: Vector4d,
): Vector4d

private external fun matrix4dPointPrimitive(
    matrix: Matrix4d,
    point: Vector3d,
): Vector4d

private external fun matrix4dDirectionPrimitive(
    matrix: Matrix4d,
    direction: Vector3d,
): Vector4d
