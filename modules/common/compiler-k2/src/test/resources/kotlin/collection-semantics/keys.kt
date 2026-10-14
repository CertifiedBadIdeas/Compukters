/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

fun main() {
    val lookup = mutableMapOf<List<Int>, Int>(listOf(1, 2) to 7)
    check(lookup[listOf(1, 2)] == 7)
    println("collection keys ok")
}
