/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

class Box(var value: Int)
value class Id(val value: Int)

fun counter(seed: Int): (Boolean) -> Int {
    var count = seed
    fun next(step: Int = 1): Int { count += step; return count }
    fun read(): Int = count
    next(2)
    val viaLambda: () -> Int = { next() }
    val next: () -> Int = ::next
    val other: () -> Int = { read() + viaLambda() }
    return { if (it) next() else other() }
}
fun transitive(seed: Int): () -> Int {
    fun sum(depth: Int): Int = if (depth == 0) seed else 1 + sum(depth - 1)
    fun make(): () -> Int {
        fun run(): Int = sum(3)
        return ::run
    }
    return make()
}
fun shared(box: Box): () -> Int {
    fun update(): Int { box.value += 1; return box.value }
    return ::update
}
fun wide(seed: Long): () -> Long {
    var count = seed
    fun next(): Long { count += 1L; return count }
    return ::next
}
fun absent(): () -> String? {
    var text: String? = null
    fun read(): String? = text
    fun update() { text = "changed" }
    val result = ::read
    update()
    return result
}
fun identity(value: Id): () -> Id { fun read(): Id = value; return ::read }
fun defaults(): Int {
    var count = 2
    fun mark(): Int { count += 1; return count }
    fun calculate(first: Int = mark(), second: Int = first + count): Int = first * 10 + second
    return calculate()
}
class Member(val seed: Int) {
    fun reference(): () -> Int { fun read(): Int = seed; return ::read }
}
fun failure(): Int {
    var state = 0
    fun fail(): Int { state += 1; throw IllegalArgumentException("local") }
    try { fail() } catch (caught: IllegalArgumentException) { check(caught.message == "local") }
    return state
}
fun nullablePrimitive(): () -> Int? {
    var value: Int? = null
    fun read(): Int? = value
    fun update() { value = 7 }
    val result = ::read
    update()
    return result
}
fun primitiveCaptures(
    b: Byte, s: Short, c: Char, flag: Boolean, f: Float, d: Double,
    ub: UByte, us: UShort, u: UInt, ul: ULong,
): () -> Boolean {
    fun read(): Boolean = b == 1.toByte() && s == 2.toShort() && c == 'x' && flag && f == 1.5f && d == 2.5 &&
        ub == 3.toUByte() && us == 4.toUShort() && u == 5u && ul == 6uL
    return ::read
}
fun localInLambda(seed: Int): () -> Int {
    val make: (Int) -> (() -> Int) = { start ->
        var count = start
        fun next(): Int { count += 1; return count }
        ::next
    }
    return make(seed)
}
class Initialized(seed: Int) {
    var value: Int = 0
    init {
        fun calculate(): Int = seed + 1
        value = calculate()
    }
}
fun referenceDependency(seed: Int): () -> Int {
    fun read(): Int = seed
    fun make(): () -> Int = ::read
    return make()
}

fun referenceDefault(seed: Int): () -> Int {
    fun read(value: Int = seed): Int = value
    return ::read
}

fun main() {
    val first = counter(0)
    val second = counter(10)
    check(first(true) == 3)
    check(first(false) == 7)
    check(first(true) == 5)
    check(second(true) == 13)
    check(transitive(7)() == 10)
    check(referenceDefault(41)() == 41)
    val box = Box(1)
    val update = shared(box)
    check(update() == 2 && box.value == 2)
    box.value = 9
    check(update() == 10)
    check(wide(0x123456789L)() == 0x12345678AL)
    check(absent()() == "changed")
    check(identity(Id(9))().value == 9)
    check(defaults() == 36)
    check(Member(23).reference()() == 23)
    check(failure() == 1)
    if (nullablePrimitive()() != 7) throw IllegalStateException("nullable primitive capture")
    if (!primitiveCaptures(1.toByte(), 2.toShort(), 'x', true, 1.5f, 2.5,
        3.toUByte(), 4.toUShort(), 5u, 6uL)()) throw IllegalStateException("primitive captures")
    val localLambda = localInLambda(4)
    if (localLambda() != 5 || localLambda() != 6) throw IllegalStateException("local in lambda")
    val initialized = Initialized(7).value
    if (initialized != 8) throw IllegalStateException("local initializer: " + initialized)
    if (referenceDependency(37)() != 37) throw IllegalStateException("reference dependency")
    var one: () -> Int = { 0 }
    var two: () -> Int = { 0 }
    for (index in 1..2) {
        var value = index * 10
        fun next(): Int { value += 1; return value }
        if (index == 1) one = ::next else two = ::next
    }
    check(one() == 11 && two() == 21 && one() == 12)
    fun recursive(depth: Int): Int = if (depth == 0) 1 else recursive(depth - 1) + 1
    val recurse: (Int) -> Int = ::recursive
    check(recurse(5) == 6)
}
