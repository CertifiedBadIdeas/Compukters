/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.math

/** Unit-quaternion spatial rotation. Use fromQuaternion() to validate and normalize input. */
public value class Rotation3d private constructor(
    val quaternion: Quaterniond,
) {
    /** Composition applying other first, then this rotation. */
    public operator fun times(other: Rotation3d): Rotation3d = fromQuaternion(quaternion * other.quaternion)

    public fun inverse(): Rotation3d = Rotation3d(quaternion.conjugated())

    public fun transform(vector: Vector3d): Vector3d {
        val axis = Vector3d(quaternion.x, quaternion.y, quaternion.z)
        val twiceCross = (axis cross vector) * 2.0
        return vector + twiceCross * quaternion.w + (axis cross twiceCross)
    }

    public fun toFloat(): Rotation3f = Rotation3f.fromQuaternion(quaternion.toFloat())

    public companion object {
        public fun identity(): Rotation3d = Rotation3d(Quaterniond(0.0, 0.0, 0.0, 1.0))

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
