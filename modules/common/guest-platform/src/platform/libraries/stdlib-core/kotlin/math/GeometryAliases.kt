/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.math

/** A position expressed as a vector; this alias adds no distinct type or validation. */
public typealias Position2i = Vector2i

/** A size expressed as a vector; this alias adds no distinct type or validation. */
public typealias Size2i = Vector2i

/** A position expressed as a vector; this alias adds no distinct type or validation. */
public typealias Position2f = Vector2f

/** A size expressed as a vector; this alias adds no distinct type or validation. */
public typealias Size2f = Vector2f

/** A position expressed as a vector; this alias adds no distinct type or validation. */
public typealias Position3i = Vector3i

/** A size expressed as a vector; this alias adds no distinct type or validation. */
public typealias Size3i = Vector3i

/** A position expressed as a vector; this alias adds no distinct type or validation. */
public typealias Position3f = Vector3f

/** A size expressed as a vector; this alias adds no distinct type or validation. */
public typealias Size3f = Vector3f

public infix fun Int.xy(y: Int): Vector2i = Vector2i(this, y)
public infix fun Float.xy(y: Float): Vector2f = Vector2f(this, y)

/** A size-oriented synonym for xy; negative components remain valid vectors. */
public infix fun Int.by(height: Int): Size2i = Vector2i(this, height)
public infix fun Float.by(height: Float): Size2f = Vector2f(this, height)

public infix fun Vector2i.sized(size: Size2i): Rect2i = Rect2i(this, size)
public infix fun Vector2f.sized(size: Size2f): Rect2f = Rect2f(this, size)
