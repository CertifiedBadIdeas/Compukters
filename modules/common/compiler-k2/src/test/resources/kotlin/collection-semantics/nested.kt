/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

fun main() {
    check(listOf(listOf(1, 2)) == listOf(listOf<Any?>(1, 2)))
    println("collection nested ok")
}
