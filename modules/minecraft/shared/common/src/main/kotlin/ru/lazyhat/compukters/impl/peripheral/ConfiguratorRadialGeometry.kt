/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.peripheral

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Shared hit geometry: the dead center and outside edge both cancel selection. */
object ConfiguratorRadialGeometry {
    const val INNER = 28
    const val OUTER = 102
    const val LABEL_RADIUS = 66
    val modes = listOf("name", "network", "display", "keyboard")

    fun select(
        x: Double,
        y: Double,
        count: Int = modes.size,
    ): Int? {
        require(count in 1..16)
        if (!x.isFinite() || !y.isFinite()) return null
        val radius = x * x + y * y
        if (radius < INNER * INNER || radius > OUTER * OUTER) return null
        val step = 2.0 * PI / count
        val angle = (atan2(y, x) + PI / 2.0 + step / 2.0 + 2.0 * PI) % (2.0 * PI)
        return (angle / step).toInt().coerceIn(0, count - 1)
    }

    data class Span(
        val x: Int,
        val y: Int,
        val width: Int,
        val sector: Int,
    )

    val spans: List<Span> by lazy {
        buildList {
            for (y in -OUTER..OUTER) {
                var start = -OUTER
                var previous = select(start.toDouble(), y.toDouble())
                for (x in (-OUTER + 1)..(OUTER + 1)) {
                    val next = if (x > OUTER) null else select(x.toDouble(), y.toDouble())
                    if (next != previous) {
                        previous?.let { add(Span(start, y, x - start, it)) }
                        start = x
                        previous = next
                    }
                }
            }
        }
    }

    fun labelX(index: Int): Int = (sin(index * 2.0 * PI / modes.size) * LABEL_RADIUS).toInt()

    fun labelY(index: Int): Int = (-cos(index * 2.0 * PI / modes.size) * LABEL_RADIUS).toInt()
}
