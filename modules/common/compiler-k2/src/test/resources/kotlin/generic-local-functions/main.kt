/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

class Box(val value: Int)

fun <T> counter(seed: T, transform: (T) -> T): () -> T {
    var current = seed
    fun next(value: T = current): T {
        current = transform(value)
        return current
    }
    fun make(): () -> T = ::next
    return make()
}

fun <T> wrapped(seed: T, transform: (T) -> T): () -> T = counter(seed, transform)

fun <T> choose(value: T): T {
    fun <R> second(first: R, fallback: T = value): T = fallback
    return second(3L)
}

fun <T> recursive(value: T, count: Int): T {
    fun read(): T = value
    return if (count == 0) read() else recursive(value, count - 1)
}

fun <T> replace(seed: T): (T) -> T {
    var current = seed
    fun <R> relay(marker: R, value: T = current): T { current = value; return current }
    return { relay(1L, it) }
}

class Holder(val seed: Int) {
    var value: Int = 0
    val chosen = choose(29)
    init {
        fun <T> use(value: T): Int = seed
        this.value = use(2L)
    }
    fun factory(): () -> Int {
        fun <T> read(value: T): Int = seed
        val call: (String) -> Int = ::read
        return { call("x") }
    }
}

class Defaults(val value: Int = choose(31))

fun main() {
    val holder = Holder(27)
    check(holder.value == 27)
    check(holder.chosen == 29)
    check(holder.factory()() == 27)
    check(Defaults().value == 31)
    var calls = 0
    fun <T> twice(value: T, transform: (T) -> T): T {
        calls += 1
        return transform(transform(value))
    }
    check(twice(3) { it + 1 } == 5)
    check(twice<Long>(10L) { it + 2L } == 14L)
    val box = Box(7)
    check(twice(box) { it } === box)
    check(calls == 3)

    fun <T> identity(value: T): T = value
    check(identity(true))
    check(identity(1.toByte()) == 1.toByte())
    check(identity(2.toShort()) == 2.toShort())
    check(identity(3) == 3)
    check(identity(4L) == 4L)
    check(identity(5.toUByte()) == 5.toUByte())
    check(identity(6.toUShort()) == 6.toUShort())
    check(identity(7u) == 7u)
    check(identity(8uL) == 8uL)
    check(identity(1.5f) == 1.5f)
    check(identity(2.5) == 2.5)
    check(identity('x') == 'x')
    check(identity<String?>(null) == null)
    check(identity<Int?>(null) == null)
    check(identity<Int?>(9) == 9)
    val id: (Int) -> Int = ::identity
    check(id(17) == 17)

    fun <T> repeat(value: T, count: Int): T = if (count == 0) value else repeat(value, count - 1)
    check(repeat(6, 3) == 6)
    check(repeat(box, 2) === box)

    fun <T> depth(value: T, count: Int): Int = if (count == 0) 0 else 1 + depth(0L, count - 1)
    check(depth("start", 3) == 3)
    val replacement = replace(0)
    check(replacement(15) == 15)
    check(replacement(16) == 16)
    val replaceBox = replace(box)
    check(replaceBox(box) === box)

    val first = wrapped(0) { it + 1 }
    val other = counter(10) { it + 2 }
    val wide = counter(100L) { it + 3L }
    val alias = first
    check(first() == 1)
    check(alias() == 2)
    check(other() == 12)
    check(wide() == 103L)
    check(first() == 3)
    val refs = counter(box) { it }
    check(refs() === box)
    val optional = counter<String?>(null) { if (it == null) "ready" else it }
    check(optional() == "ready")
    check(counter(true) { !it }() == false)
    check(counter(1.toByte()) { (it + 1).toByte() }() == 2.toByte())
    check(counter(2.toShort()) { (it + 1).toShort() }() == 3.toShort())
    check(counter(3.toUByte()) { (it + 1u).toUByte() }() == 4.toUByte())
    check(counter(4.toUShort()) { (it + 1u).toUShort() }() == 5.toUShort())
    check(counter(5u) { it + 1u }() == 6u)
    check(counter(6uL) { it + 1uL }() == 7uL)
    check(counter(1.5f) { it + 0.5f }() == 2.0f)
    check(counter(2.5) { it + 0.5 }() == 3.0)
    check(counter('a') { it + 1 }() == 'b')
    check(choose(19) == 19)
    check(choose(box) === box)
    check(recursive(23, 3) == 23)
    check(recursive(box, 2) === box)
}
