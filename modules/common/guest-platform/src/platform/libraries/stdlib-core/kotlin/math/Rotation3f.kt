/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.math

/**
 * Spatial rotation with unit length up to Float rounding. Factories validate and normalize input.
 * Uses right-handed active rotations of column vectors; a * b applies b first.
 */
public value class Rotation3f private constructor(
    val quaternion: Quaternionf,
) {
    /** Composition applying other first, then this rotation. */
    public operator fun times(other: Rotation3f): Rotation3f = (toDouble() * other.toDouble()).toFloat()

    /** Conjugation preserves the existing unit-length error; no additional normalization is needed. */
    public fun inverse(): Rotation3f = Rotation3f(quaternion.conjugated())

    public fun transform(vector: Vector3f): Vector3f {
        val axis = Vector3f(quaternion.x, quaternion.y, quaternion.z)
        val twiceCross = (axis cross vector) * 2f
        return vector + twiceCross * quaternion.w + (axis cross twiceCross)
    }

    /** Smallest spatial angle in [0, PI], independent of the quaternion's sign. */
    public fun angleTo(other: Rotation3f): Float = toDouble().angleTo(other.toDouble()).toFloat()

    /**
     * Shortest-arc interpolation with Double intermediates; finite amounts outside 0..1 extrapolate.
     * Huge angles lose trigonometric argument precision. Near PI the shortest direction can switch.
     */
    public fun slerp(other: Rotation3f, amount: Float): Rotation3f {
        if (amount == 0f) return this
        if (amount == 1f) return other
        return toDouble().slerp(other.toDouble(), amount.toDouble()).toFloat()
    }

    public fun toMatrix3f(): Matrix3f {
        val matrix = toDouble().toMatrix3d()
        return Matrix3f(
            matrix.m00.toFloat(), matrix.m01.toFloat(), matrix.m02.toFloat(),
            matrix.m10.toFloat(), matrix.m11.toFloat(), matrix.m12.toFloat(),
            matrix.m20.toFloat(), matrix.m21.toFloat(), matrix.m22.toFloat(),
        )
    }

    public fun toMatrix4f(): Matrix4f {
        val matrix = toMatrix3f()
        return Matrix4f(
            matrix.m00, matrix.m01, matrix.m02, 0f,
            matrix.m10, matrix.m11, matrix.m12, 0f,
            matrix.m20, matrix.m21, matrix.m22, 0f,
            0f, 0f, 0f, 1f,
        )
    }

    public fun toDouble(): Rotation3d = Rotation3d.fromQuaternion(quaternion.toDouble())

    public companion object {
        public fun identity(): Rotation3f = Rotation3f(Quaternionf(0f, 0f, 0f, 1f))

        /**
         * Shortest rotation between finite nonzero directions, with Double intermediates and Float storage.
         * Magnitudes are ignored. Parallel directions return identity; opposite directions choose the
         * source crossed with its least aligned coordinate axis (ties X, then Y, then Z), as in Rotation3d.
         */
        public fun fromTo(from: Vector3f, to: Vector3f): Rotation3f =
            Rotation3d.fromTo(from.toDouble(), to.toDouble()).toFloat()

        /** Right-handed axis-angle rotation. The axis must be finite and nonzero; the angle is in radians. */
        public fun fromAxisAngle(axis: Vector3f, angleRadians: Float): Rotation3f =
            Rotation3d.fromAxisAngle(axis.toDouble(), angleRadians.toDouble()).toFloat()

        public fun rotationX(angleRadians: Float): Rotation3f = fromAxisAngle(Vector3f(1f, 0f, 0f), angleRadians)

        public fun rotationY(angleRadians: Float): Rotation3f = fromAxisAngle(Vector3f(0f, 1f, 0f), angleRadians)

        public fun rotationZ(angleRadians: Float): Rotation3f = fromAxisAngle(Vector3f(0f, 0f, 1f), angleRadians)

        /** Reject zero and non-finite quaternions; finite nonzero components are normalized. */
        public fun fromQuaternion(value: Quaternionf): Rotation3f {
            if (!(value.x >= -Float.MAX_VALUE && value.x <= Float.MAX_VALUE &&
                    value.y >= -Float.MAX_VALUE && value.y <= Float.MAX_VALUE &&
                    value.z >= -Float.MAX_VALUE && value.z <= Float.MAX_VALUE &&
                    value.w >= -Float.MAX_VALUE && value.w <= Float.MAX_VALUE)
            ) {
                throw IllegalArgumentException("Rotation requires finite quaternion components.")
            }
            if (value.x == 0f && value.y == 0f && value.z == 0f && value.w == 0f) {
                throw IllegalArgumentException("Rotation requires a nonzero quaternion.")
            }
            return Rotation3f(value.toVector4f().normalized().toQuaternionf())
        }
    }
}
