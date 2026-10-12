/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.math

/**
 * Spatial rotation with unit length up to Double rounding. Factories validate and normalize input.
 * Uses right-handed active rotations of column vectors; a * b applies b first.
 */
public value class Rotation3d private constructor(
    val quaternion: Quaterniond,
) {
    /** The quaternion's x component. */
    public val x: Double
        get() = quaternion.x

    /** The quaternion's y component. */
    public val y: Double
        get() = quaternion.y

    /** The quaternion's z component. */
    public val z: Double
        get() = quaternion.z

    /** The quaternion's w component. */
    public val w: Double
        get() = quaternion.w

    /** Composition applying other first, then this rotation. */
    public operator fun times(other: Rotation3d): Rotation3d = fromQuaternion(quaternion * other.quaternion)

    /** Conjugation preserves the existing unit-length error; no additional normalization is needed. */
    public fun inverse(): Rotation3d = Rotation3d(quaternion.conjugated())

    public fun transform(vector: Vector3d): Vector3d {
        val axis = Vector3d(quaternion.x, quaternion.y, quaternion.z)
        val twiceCross = (axis cross vector) * 2.0
        return vector + twiceCross * quaternion.w + (axis cross twiceCross)
    }

    /** Smallest spatial angle in [0, PI], independent of the quaternion's sign. */
    public fun angleTo(other: Rotation3d): Double {
        val relative = quaternion.conjugated() * other.quaternion
        return 2.0 * atan2(relative.toVector4d().xyz.length, abs(relative.w))
    }

    /**
     * Interpolate along the shortest arc; finite amounts outside 0..1 extrapolate if the scaled angle is finite.
     * Zero/one preserve the exact endpoint representation. Huge angles lose trigonometric argument precision.
     * At PI the shortest arc is ambiguous; near PI small changes can switch the selected direction.
     */
    public fun slerp(other: Rotation3d, amount: Double): Rotation3d {
        if (!(amount >= -Double.MAX_VALUE && amount <= Double.MAX_VALUE)) {
            throw IllegalArgumentException("Rotation interpolation requires a finite amount.")
        }
        if (amount == 0.0) return this
        if (amount == 1.0) return other
        val relative = fromQuaternion(quaternion.conjugated() * other.quaternion).quaternion
        val axis = if (relative.w < 0.0) -relative.toVector4d().xyz else relative.toVector4d().xyz
        val length = axis.length
        if (length == 0.0) return this
        val halfAngle = atan2(length, abs(relative.w)) * amount
        if (!(halfAngle >= -Double.MAX_VALUE && halfAngle <= Double.MAX_VALUE)) {
            throw IllegalArgumentException("Rotation interpolation angle exceeds Double range.")
        }
        val direction = axis.normalized()
        val sine = sin(halfAngle)
        val delta = Quaterniond(direction.x * sine, direction.y * sine, direction.z * sine, cos(halfAngle))
        return fromQuaternion(quaternion * delta)
    }

    public fun toMatrix3d(): Matrix3d {
        val x = quaternion.x
        val y = quaternion.y
        val z = quaternion.z
        val w = quaternion.w
        return Matrix3d(
            1.0 - 2.0 * (y * y + z * z), 2.0 * (x * y - w * z), 2.0 * (x * z + w * y),
            2.0 * (x * y + w * z), 1.0 - 2.0 * (x * x + z * z), 2.0 * (y * z - w * x),
            2.0 * (x * z - w * y), 2.0 * (y * z + w * x), 1.0 - 2.0 * (x * x + y * y),
        )
    }

    public fun toMatrix4d(): Matrix4d {
        val matrix = toMatrix3d()
        return Matrix4d(
            matrix.m00, matrix.m01, matrix.m02, 0.0,
            matrix.m10, matrix.m11, matrix.m12, 0.0,
            matrix.m20, matrix.m21, matrix.m22, 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
    }

    public fun toFloat(): Rotation3f = Rotation3f.fromQuaternion(quaternion.toFloat())

    public companion object {
        public fun identity(): Rotation3d = Rotation3d(Quaterniond(0.0, 0.0, 0.0, 1.0))

        /**
         * Shortest rotation from one finite nonzero direction to another; magnitudes are ignored.
         * Parallel directions return identity. Opposite directions use from crossed with the coordinate
         * axis whose absolute component in from is smallest (ties prefer X, then Y, then Z).
         * This antiparallel axis is deterministic, but no continuous choice exists around that case.
         */
        public fun fromTo(from: Vector3d, to: Vector3d): Rotation3d {
            if (!(from.x >= -Double.MAX_VALUE && from.x <= Double.MAX_VALUE &&
                    from.y >= -Double.MAX_VALUE && from.y <= Double.MAX_VALUE &&
                    from.z >= -Double.MAX_VALUE && from.z <= Double.MAX_VALUE &&
                    to.x >= -Double.MAX_VALUE && to.x <= Double.MAX_VALUE &&
                    to.y >= -Double.MAX_VALUE && to.y <= Double.MAX_VALUE &&
                    to.z >= -Double.MAX_VALUE && to.z <= Double.MAX_VALUE)
            ) {
                throw IllegalArgumentException("Direction rotation requires finite vectors.")
            }
            if ((from.x == 0.0 && from.y == 0.0 && from.z == 0.0) ||
                (to.x == 0.0 && to.y == 0.0 && to.z == 0.0)
            ) {
                throw IllegalArgumentException("Direction rotation requires nonzero vectors.")
            }
            val source = from.normalized()
            val target = to.normalized()
            val axis = source cross target
            val sine = axis.length
            val cosine = source dot target
            if (sine != 0.0) return fromAxisAngle(axis, atan2(sine, cosine))
            if (cosine >= 0.0) return identity()
            val x = abs(source.x)
            val y = abs(source.y)
            val z = abs(source.z)
            val basis = if (x <= y && x <= z) Vector3d(1.0, 0.0, 0.0)
                else if (y <= z) Vector3d(0.0, 1.0, 0.0)
                else Vector3d(0.0, 0.0, 1.0)
            return fromAxisAngle(source cross basis, PI)
        }

        /** Right-handed axis-angle rotation. The axis must be finite and nonzero; the angle is in radians. */
        public fun fromAxisAngle(axis: Vector3d, angleRadians: Double): Rotation3d {
            if (!(axis.x >= -Double.MAX_VALUE && axis.x <= Double.MAX_VALUE &&
                    axis.y >= -Double.MAX_VALUE && axis.y <= Double.MAX_VALUE &&
                    axis.z >= -Double.MAX_VALUE && axis.z <= Double.MAX_VALUE &&
                    angleRadians >= -Double.MAX_VALUE && angleRadians <= Double.MAX_VALUE)
            ) {
                throw IllegalArgumentException("Axis-angle rotation requires finite axis and angle.")
            }
            if (axis.x == 0.0 && axis.y == 0.0 && axis.z == 0.0) {
                throw IllegalArgumentException("Axis-angle rotation requires a nonzero axis.")
            }
            val direction = axis.normalized()
            val halfAngle = angleRadians / 2.0
            val sine = sin(halfAngle)
            return fromQuaternion(Quaterniond(direction.x * sine, direction.y * sine, direction.z * sine, cos(halfAngle)))
        }

        public fun rotationX(angleRadians: Double): Rotation3d = fromAxisAngle(Vector3d(1.0, 0.0, 0.0), angleRadians)

        public fun rotationY(angleRadians: Double): Rotation3d = fromAxisAngle(Vector3d(0.0, 1.0, 0.0), angleRadians)

        public fun rotationZ(angleRadians: Double): Rotation3d = fromAxisAngle(Vector3d(0.0, 0.0, 1.0), angleRadians)

        /** Reject zero and non-finite quaternions; finite nonzero components are normalized. */
        public fun fromQuaternion(value: Quaterniond): Rotation3d {
            if (!(value.x >= -Double.MAX_VALUE && value.x <= Double.MAX_VALUE &&
                    value.y >= -Double.MAX_VALUE && value.y <= Double.MAX_VALUE &&
                    value.z >= -Double.MAX_VALUE && value.z <= Double.MAX_VALUE &&
                    value.w >= -Double.MAX_VALUE && value.w <= Double.MAX_VALUE)
            ) {
                throw IllegalArgumentException("Rotation requires finite quaternion components.")
            }
            if (value.x == 0.0 && value.y == 0.0 && value.z == 0.0 && value.w == 0.0) {
                throw IllegalArgumentException("Rotation requires a nonzero quaternion.")
            }
            return Rotation3d(value.toVector4d().normalized().toQuaterniond())
        }
    }
}
