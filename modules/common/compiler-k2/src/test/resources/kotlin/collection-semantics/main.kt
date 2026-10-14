/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

fun main() {
    val first: Any = listOf(1, 2, 3)
    val second: Any = listOf<Any?>(1, 2, 3)
    check(first == second)
    check(second == first)
    check(first.hashCode() == 30817)
    check(first.hashCode() == second.hashCode())
    check(first.toString() == "[1, 2, 3]")
    check(first != listOf(3, 2, 1))
    check(first != setOf(1, 2, 3))
    check(listOf<String?>(null, "hi").toString() == "[null, hi]")
    check(listOf<Int>() == listOf<String>())
    check(listOf<Int>().hashCode() == 1)
    check(listOf<Int>().toString() == "[]")

    val a: Any = setOf(1, 2, 3)
    val b: Any = setOf<Any?>(3, 2, 1)
    check(a == b && b == a)
    check(a.hashCode() == 6 && b.hashCode() == 6)
    check(setOf<Int>().toString() == "[]")
    check(setOf<Int>().hashCode() == 0)
    check(setOf<Int>() == setOf<String>())
    check(setOf<String?>(null).toString() == "[null]")

    println("collection semantics ok")
}
