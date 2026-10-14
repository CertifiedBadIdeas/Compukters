/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

fun flightBound(
    value: Double,
    minimum: Double,
    maximum: Double,
): Double {
    require(value >= -Double.MAX_VALUE && value <= Double.MAX_VALUE) { "Nonfinite flight command" }
    return if (value < minimum) {
        minimum
    } else if (value > maximum) {
        maximum
    } else {
        value
    }
}
