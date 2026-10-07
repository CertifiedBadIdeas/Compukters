/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import kotlin.collections.*

fun main() {
    val minimum = mapOf("iron" to 12, "copper" to 8)
    val deliveries = listOf("iron" to 5, "copper" to 3, "iron" to 9, "gold" to 2)
    val totals = mutableMapOf<String, Int>()
    val seen = mutableSetOf<String>()

    for ((item, amount) in deliveries) {
        totals[item] = totals.getOrPut(item) { 0 } + amount
        seen.add(item)
    }

    // Choose a display order explicitly; hash-table iteration order is unspecified.
    for (item in listOf("iron", "copper", "gold")) {
        val count = totals[item] ?: 0
        val required = minimum[item] ?: 0
        val status = if (count >= required) "ready" else "low"
        println("$item: $count / $required ($status)")
    }
    println("Distinct items: ${seen.size}")
}
