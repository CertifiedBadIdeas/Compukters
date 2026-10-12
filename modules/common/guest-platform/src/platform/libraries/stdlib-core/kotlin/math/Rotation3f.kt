/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.math

/** Unit-quaternion spatial rotation. Use fromQuaternion() to validate and normalize input. */
public value class Rotation3f private constructor(
    val quaternion: Quaternionf,
) {
    /** Composition applying other first, then this rotation. */
    public operator fun times(other: Rotation3f): Rotation3f = fromQuaternion(quaternion * other.quaternion)

    public fun inverse(): Rotation3f = Rotation3f(quaternion.conjugated())

    public fun transform(vector: Vector3f): Vector3f {
        val axis = Vector3f(quaternion.x, quaternion.y, quaternion.z)
        val twiceCross = (axis cross vector) * 2f
        return vector + twiceCross * quaternion.w + (axis cross twiceCross)
    }

    public fun toDouble(): Rotation3d = Rotation3d.fromQuaternion(quaternion.toDouble())

    public companion object {
        public fun identity(): Rotation3f = Rotation3f(Quaternionf(0f, 0f, 0f, 1f))

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
