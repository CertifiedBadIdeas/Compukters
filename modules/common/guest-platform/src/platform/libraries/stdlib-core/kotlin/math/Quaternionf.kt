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
public value class Quaternionf(
    val x: Float,
    val y: Float,
    val z: Float,
    val w: Float,
) {
    public fun toDouble(): Quaterniond = Quaterniond(x.toDouble(), y.toDouble(), z.toDouble(), w.toDouble())

    public fun toVector4f(): Vector4f = Vector4f(x, y, z, w)
}

/** Interpret components as a quaternion without changing their values. */
public fun Vector4f.toQuaternionf(): Quaternionf = Quaternionf(x, y, z, w)
