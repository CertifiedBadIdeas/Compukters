/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.math

/** Half-open rectangle: left/top included, right/bottom excluded. Zero-sized rectangles are empty. */
public value class Rect2i(
    val position: Position2i,
    val size: Size2i,
) {
    init {
        if (!(size.x >= 0 && size.y >= 0)) throw IllegalArgumentException("Negative rectangle size")
        if (!(position.x.toLong() + size.x in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())) {
            throw IllegalArgumentException(
                "Rectangle x extent overflows",
            )
        }
        if (!(position.y.toLong() + size.y in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())) {
            throw IllegalArgumentException(
                "Rectangle y extent overflows",
            )
        }
    }

    public val left: Int get() = position.x
    public val top: Int get() = position.y
    public val right: Int get() = position.x + size.x
    public val bottom: Int get() = position.y + size.y
    public val isEmpty: Boolean get() = size.x == 0 || size.y == 0

    public operator fun contains(point: Vector2i): Boolean = point.x >= left && point.x < right && point.y >= top && point.y < bottom

    public operator fun contains(other: Rect2i): Boolean =
        other.isEmpty || (!isEmpty && other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom)

    public fun intersects(other: Rect2i): Boolean =
        !isEmpty && !other.isEmpty && left < other.right && other.left < right && top < other.bottom && other.top < bottom

    public fun intersection(other: Rect2i): Rect2i {
        val x = max(left, other.left)
        val y = max(top, other.top)
        val endX = min(right, other.right)
        val endY = min(bottom, other.bottom)
        if (endX <= x || endY <= y) return Rect2i(Vector2i(x, y), Vector2i(0, 0))
        return Rect2i(Vector2i(x, y), Vector2i(endX - x, endY - y))
    }

    /** Smallest bounding rectangle; empty inputs do not enlarge it. */
    public fun union(other: Rect2i): Rect2i {
        if (isEmpty) return other
        if (other.isEmpty) return this
        val x = min(left, other.left)
        val y = min(top, other.top)
        val width = max(right, other.right).toLong() - x
        val height = max(bottom, other.bottom).toLong() - y
        if (!(width <= Int.MAX_VALUE && height <= Int.MAX_VALUE)) throw IllegalArgumentException("Rectangle size overflows")
        return Rect2i(Vector2i(x, y), Vector2i(width.toInt(), height.toInt()))
    }
}

/** Half-open rectangle: left/top included, right/bottom excluded. Zero-sized rectangles are empty. */
public value class Rect2f(
    val position: Position2f,
    val size: Size2f,
) {
    init {
        if (!(size.x >= 0 && size.y >= 0)) throw IllegalArgumentException("Negative rectangle size")
        if (!(
                abs(
                    position.x,
                ) <= Float.MAX_VALUE && abs(position.y) <= Float.MAX_VALUE
            )
        ) {
            throw IllegalArgumentException("Non-finite rectangle position")
        }
        if (!(size.x <= Float.MAX_VALUE && size.y <= Float.MAX_VALUE)) throw IllegalArgumentException("Non-finite rectangle size")
        if (!(
                abs(
                    position.x + size.x,
                ) <= Float.MAX_VALUE && abs(
                    position.y + size.y,
                ) <= Float.MAX_VALUE
            )
        ) {
            throw IllegalArgumentException("Non-finite rectangle extent")
        }
    }

    public val left: Float get() = position.x
    public val top: Float get() = position.y
    public val right: Float get() = position.x + size.x
    public val bottom: Float get() = position.y + size.y
    public val isEmpty: Boolean get() = right <= left || bottom <= top

    public operator fun contains(point: Vector2f): Boolean = point.x >= left && point.x < right && point.y >= top && point.y < bottom

    public operator fun contains(other: Rect2f): Boolean =
        other.isEmpty || (!isEmpty && other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom)

    public fun intersects(other: Rect2f): Boolean =
        !isEmpty && !other.isEmpty && left < other.right && other.left < right && top < other.bottom && other.top < bottom

    public fun intersection(other: Rect2f): Rect2f {
        val x = max(left, other.left)
        val y = max(top, other.top)
        val endX = min(right, other.right)
        val endY = min(bottom, other.bottom)
        if (endX <= x || endY <= y) return Rect2f(Vector2f(x, y), Vector2f(0f, 0f))
        return Rect2f(Vector2f(x, y), Vector2f(endX - x, endY - y))
    }

    /** Smallest bounding rectangle; empty inputs do not enlarge it. */
    public fun union(other: Rect2f): Rect2f {
        if (isEmpty) return other
        if (other.isEmpty) return this
        val x = min(left, other.left)
        val y = min(top, other.top)
        return Rect2f(Vector2f(x, y), Vector2f(max(right, other.right) - x, max(bottom, other.bottom) - y))
    }
}
