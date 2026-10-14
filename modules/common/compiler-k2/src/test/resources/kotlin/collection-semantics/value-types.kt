/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

private enum class Mode { ON, OFF }
private value class Identifier(val value: Int)
private value class OtherIdentifier(val value: Int)

fun main() {
    check(Mode.entries == listOf(Mode.ON, Mode.OFF))
    check(listOf(Mode.ON, Mode.OFF) == Mode.entries)
    check(Mode.entries.hashCode() == listOf(Mode.ON, Mode.OFF).hashCode())
    check(Mode.entries.toString() == listOf(Mode.ON, Mode.OFF).toString())
    check(listOf(Identifier(1)) == listOf<Any?>(Identifier(1)))
    check(listOf(Identifier(1)) != listOf<Any?>(OtherIdentifier(1)))
    check(setOf(Identifier(1)) == setOf<Any?>(Identifier(1)))
    check(mapOf(Identifier(1) to Identifier(2)) == mapOf<Any?, Any?>(Identifier(1) to Identifier(2)))
    check(listOf(Identifier(1), null) == listOf<Any?>(Identifier(1), null))
    check(listOf(1) != listOf(1L))
    check(setOf(1) != setOf(1L))
    check(mapOf(1 to 2) != mapOf(1L to 2))
    println("collection value-types ok")
}
