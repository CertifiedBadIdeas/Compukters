/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.math

/**
 * Immutable quaternion components in x, y, z, w order, distinct from a four-dimensional vector.
 * Construction and conversion preserve components; they do not normalize or validate a rotation.
 */
public value class Quaterniond(
    val x: Double,
    val y: Double,
    val z: Double,
    val w: Double,
) {
    public fun toFloat(): Quaternionf = Quaternionf(x.toFloat(), y.toFloat(), z.toFloat(), w.toFloat())

    /** Hamilton product; no normalization is performed. */
    public operator fun times(other: Quaterniond): Quaterniond =
        Quaterniond(
            w * other.x + x * other.w + y * other.z - z * other.y,
            w * other.y - x * other.z + y * other.w + z * other.x,
            w * other.z + x * other.y - y * other.x + z * other.w,
            w * other.w - x * other.x - y * other.y - z * other.z,
        )

    public fun conjugated(): Quaterniond = Quaterniond(-x, -y, -z, w)

    public fun toVector4d(): Vector4d = Vector4d(x, y, z, w)
}

/** Interpret components as a quaternion without changing their values. */
public fun Vector4d.toQuaterniond(): Quaterniond = Quaterniond(x, y, z, w)
