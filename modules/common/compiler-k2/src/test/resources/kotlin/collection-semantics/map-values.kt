/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

fun main() {
    check(mapOf(1 to listOf(2)) == mapOf(1 to listOf<Any?>(2)))
    println("collection map-values ok")
}
