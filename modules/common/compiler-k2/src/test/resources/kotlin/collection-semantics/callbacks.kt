/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

private class Element(private val id: Int) {
    var equalityCalls: Int = 0
    var hashCalls: Int = 0
    var textCalls: Int = 0
    var failure: String = ""
    override fun equals(other: Any?): Boolean {
        equalityCalls += 1
        if (failure == "equals") throw IllegalStateException("element equals")
        return other is Element && id == other.id
    }
    override fun hashCode(): Int {
        hashCalls += 1
        if (failure == "hash") throw IllegalStateException("element hash")
        return id
    }
    override fun toString(): String {
        textCalls += 1
        if (failure == "text") throw IllegalStateException("element text")
        return "element:$id"
    }
}

fun main() {
    val a = Element(1)
    val b = Element(1)
    val left = listOf(a)
    val right = listOf(b)
    check(left == right)
    check(a.equalityCalls == 1)
    check(left.equals(left))
    check(a.equalityCalls == 1)
    check(left.hashCode() == 32)
    check(a.hashCalls == 1)
    check(left.toString() == "[element:1]")
    check(a.textCalls == 1)
    a.failure = "equals"
    var caught = false
    try { left == right } catch (error: IllegalStateException) { caught = true }
    check(caught)
    a.failure = "hash"
    caught = false
    try { left.hashCode() } catch (error: IllegalStateException) { caught = true }
    check(caught)
    a.failure = "text"
    caught = false
    try { left.toString() } catch (error: IllegalStateException) { caught = true }
    check(caught)
    a.failure = ""
    val set = setOf(a)
    val map = mapOf("key" to a)
    a.failure = "hash"
    caught = false
    try { set.hashCode() } catch (error: IllegalStateException) { caught = true }
    check(caught)
    caught = false
    try { map.hashCode() } catch (error: IllegalStateException) { caught = true }
    check(caught)
    a.failure = "text"
    caught = false
    try { map.toString() } catch (error: IllegalStateException) { caught = true }
    check(caught)
    val skipped = Element(2)
    skipped.failure = "equals"
    check(listOf(Element(1), skipped) != listOf(Element(3), Element(2)))
    check(skipped.equalityCalls == 0)
    println("collection callbacks ok")
}
