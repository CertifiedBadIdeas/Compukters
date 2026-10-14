/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

fun main() {
    val map = mutableMapOf<String, Int?>("one" to 1, "two" to null)
    val other: Any = mapOf<Any?, Any?>("two" to null, "one" to 1)
    val erased: Any = map
    check(erased == other && other == erased)
    check(erased.hashCode() == other.hashCode())
    check(erased.hashCode() == (("one".hashCode() xor 1) + "two".hashCode()))
    check(map != mapOf("one" to 1, "missing" to null))
    check(mapOf<Int, String>() == mapOf<String, Int>())
    check(mapOf<Int, String>().toString() == "{}")
    check(mapOf<Int, String>().hashCode() == 0)
    check(mapOf<String?, Int?>(null to null).toString() == "{null=null}")
    check(map.keys == setOf("two", "one"))
    val values = map.values
    check(values != map.values)
    check(values.toString().startsWith("["))
    check(mapOf("x" to 7).values.toString() == "[7]")
    check(mapOf<String, Int>().values.toString() == "[]")
    val entries = map.entries
    check(entries == mapOf<Any?, Any?>("one" to 1, "two" to null).entries)
    check(entries.hashCode() == map.hashCode())
    check(mapOf("x" to 7).entries.toString() == "[x=7]")
    val entry = mapOf("x" to 7).entries.iterator().next()
    check(entry == mapOf<Any?, Any?>("x" to 7).entries.iterator().next())
    check(entry.hashCode() == ("x".hashCode() xor 7))
    check(entry.toString() == "x=7")
    map["two"] = 2
    check(entries == mapOf("one" to 1, "two" to 2).entries)
    check(values.contains(2))
    check(map != other)

    println("collection maps ok")
}
