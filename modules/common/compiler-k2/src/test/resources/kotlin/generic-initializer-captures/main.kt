/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

class Box(val value: Int)
value class Point(val x: Int, val y: Long)
class Nested<T>(val value: T)
class Arguments<T>(val first: T, val second: T)
class BoxedInitializer<T>(val value: T) {
    var boxed: Any? = null
    init { boxed = Point(3, 4L) }
}
class RecursiveOrder<T>(val value: T, depth: Int, prior: Int = -1) {
    var callback: () -> T = { value }
    init {
        if (depth > 0) {
            val child = RecursiveOrder(value, depth - 1, depth)
            callback = child.callback
        } else {
            check(prior == 1) { "recursive prior=" + prior }
        }
    }
}
class Defaulted<T>(initial: T, val callback: () -> T = { initial })
fun <T> keep(value: T): T = value
object OrderState { var value: Int = 0 }
fun mark(step: Int): Int { OrderState.value = OrderState.value * 10 + step; return step }

class Holder<T>(initial: T, next: T) {
    var value: T = initial
    val snapshot: () -> T = { initial }
    val live: () -> T = { value }
    var take: () -> T = { initial }
    var peek: () -> T = { initial }
    var nested: Nested<T>? = null
    init {
        var current = initial
        fun <R> identity(other: R): R = other
        fun read(): T = current
        fun advance(fallback: T = next): T {
            val saved = read()
            current = identity(fallback)
            return saved
        }
        take = ::advance
        peek = ::read
        nested = Nested(keep(initial))
    }
}
class Recursive<T>(val value: T, depth: Int) {
    var callback: () -> T = { value }
    init {
        if (depth > 0) {
            val child = Recursive(value, depth - 1)
            callback = child.callback
        }
    }
}
class Ordered<T>(initial: T, arg: Int) {
    val first: () -> T = { initial }
    val step = mark(2)
    var second: () -> T = { initial }
    init { mark(3); second = { initial }; check(arg == 1 && step == 2) }
    val last = mark(4)
}
fun <T> exercise(first: T, second: T) {
    val one = Holder(first, second)
    val two = Holder(second, first)
    check(one.snapshot() == first && one.live() == first)
    one.value = second
    check(one.snapshot() == first && one.live() == second)
    check(one.peek() == first && one.take() == first && one.peek() == second)
    check(one.take() == second)
    check(two.peek() == second && two.take() == second && two.peek() == first)
    check(one.nested?.value == first)
}
fun retained(seed: Int): () -> ByteArray {
    val bytes = ByteArray(12 * 1024)
    bytes[0] = seed.toByte()
    return Holder(bytes, bytes).live
}
fun main() {
    exercise(1, 2)
    exercise(1L, 2L)
    exercise(1.toByte(), 2.toByte())
    exercise(1.toShort(), 2.toShort())
    exercise(1.0f, 2.0f)
    exercise(1.0, 2.0)
    exercise(false, true)
    exercise('a', 'b')
    exercise(1u, 2u)
    exercise(1uL, 2uL)
    exercise(1u.toUByte(), 2u.toUByte())
    exercise(1u.toUShort(), 2u.toUShort())
    exercise<Int?>(null, 2)
    exercise<String?>(null, "next")
    exercise("first", "second")
    exercise(Box(1), Box(2))
    exercise(Point(1, 2L), Point(3, 4L))
    var argument = 1
    val evaluated = Arguments(argument, run { argument = 2; argument })
    check(evaluated.first == 1 && evaluated.second == 2) { "argument snapshots " + evaluated.first + "/" + evaluated.second }
    check(BoxedInitializer(3).boxed == Point(3, 4L)) { "initializer box" }
    check(RecursiveOrder(7, 3).callback() == 7) { "recursive callback" }
    check(Defaulted(3) { 7 }.callback() == 7) { "explicit callback" }
    check(Recursive(7, 3).callback() == 7)
    check(Recursive("saved", 2).callback() == "saved")
    check(Defaulted(3).callback() == 3)
    check(Defaulted("saved").callback() == "saved")
    check(Defaulted<Int?>(null).callback() == null)
    val ordered = Ordered("saved", mark(1))
    check(OrderState.value == 1234 && ordered.first() == "saved" && ordered.second() == "saved")
    val older = retained(17)
    var newer: (() -> ByteArray)? = null
    var index = 0
    while (index < 200) {
        newer = retained(index % 100)
        check(newer?.invoke()?.get(0) == (index % 100).toByte())
        check(older()[0] == 17.toByte())
        index++
    }
    check(newer?.invoke()?.get(0) == 99.toByte())
}
