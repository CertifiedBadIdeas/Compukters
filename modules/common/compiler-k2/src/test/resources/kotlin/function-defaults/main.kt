/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import kotlin.random.Random

class Trace {
    var order: Int = 0
    fun mark(value: Int): Int { order = order * 10 + value; return value }
    fun receiver(): Trace { mark(8); return this }
    fun member(first: Int = mark(1), second: Int = first + mark(2)): Int = first * 10 + second
}

fun calculate(trace: Trace, first: Int = trace.mark(1), second: Int = first + trace.mark(2), third: Int = second + trace.mark(3)): Int =
    first * 100 + second * 10 + third
fun Int.offset(delta: Int = this + 1): Int = this + delta
fun nested(first: Int = 2, second: Int = nested(first + 1, 4), third: Int = first + second): Int = first + second + third
fun recurse(value: Int, depth: Int = 0): Int = if (depth == 0) recurse(value + 1, value) else value * 10 + depth
fun <T> choose(value: T, fallback: T = value): T = fallback
class Cell<T>(val value: T)
fun <T> makeCell(value: T): Cell<T> = Cell(value)
fun <T> boxed(value: T, cell: Cell<T> = makeCell(value)): T = cell.value
fun closure(base: Int, transform: (Int) -> Int = { it + base }): Int = transform(2)
fun array(values: IntArray = intArrayOf(1, 2)): IntArray = values
fun nullable(value: String? = null): String? = value
fun wide(value: Long = 0x123456789L): Long = value
fun unsigned(value: ULong = 0xFFFFFFFFFFFFFFFFuL): ULong = value
fun roll(sides: Int = 6, random: Random = Random.Default): Int = random.nextInt(1, sides + 1)
value class Id(val value: Int)
fun identity(value: Id = Id(8)): Int = value.value
fun scalar(flag: Boolean = true, char: Char = 'x', floating: Float = 1.5f, double: Double = 2.5): Boolean =
    flag && char == 'x' && floating == 1.5f && double == 2.5
fun <T> otherSpecialization(value: T): Int = choose(4)
fun cyclic(value: Int = cyclic()): Int = value
fun snapshot(first: Int, second: Int = first): Int = first * 10 + second

open class Base(val number: Int) {
    open fun value(amount: Int = number): Int = amount
}
class Derived : Base(7) { override fun value(amount: Int): Int = amount + 10 }
interface DefaultMethod {
    fun value(first: Int = 4, second: Int = first + 1): Int
}
class Implementation : DefaultMethod { override fun value(first: Int, second: Int): Int = first * 10 + second }
class Generic<T>(val value: T) { fun read(fallback: T = value): T = fallback }

fun fail(trace: Trace): Int { trace.mark(2); throw IllegalArgumentException("default") }
fun throwing(trace: Trace, first: Int = trace.mark(1), second: Int = fail(trace), third: Int = trace.mark(3)): Int {
    trace.mark(9)
    return first + second + third
}

fun main() {
    val trace = Trace()
    check(calculate(trace, third = trace.mark(9)) == 139)
    check(trace.order == 912)
    trace.order = 0
    check(calculate(trace, second = trace.mark(2), first = trace.mark(1), third = trace.mark(3)) == 123)
    check(trace.order == 213)
    trace.order = 0
    check(trace.receiver().member(second = trace.mark(9)) == 19)
    check(trace.order == 891)
    check(5.offset() == 11)
    check(nested() == 32)
    check(recurse(3) == 43)
    check(choose(17) == 17)
    check(choose("generic") == "generic")
    check(boxed(23) == 23)
    check(boxed("cell") == "cell")
    check(identity() == 8)
    check(scalar())
    check(otherSpecialization("caller") == 4)
    check(cyclic(2) == 2)
    check(closure(5) == 7)
    check(closure(9) == 11)
    val first = array()
    first[0] = 99
    check(array()[0] == 1)
    check(nullable() == null)
    check(wide() == 0x123456789L)
    check(unsigned() == 0xFFFFFFFFFFFFFFFFuL)
    val derived = Derived()
    check(derived.value() == 17)
    val base: Base = derived
    check(base.value() == 17)
    val implementation = Implementation()
    check(implementation.value() == 45)
    val iface: DefaultMethod = implementation
    check(iface.value(second = 8) == 48)
    check(Generic(31).read() == 31)
    check(Generic("member").read() == "member")
    val expected = Random(0)
    check(roll() == expected.nextInt(1, 7))
    check(roll(12) == expected.nextInt(1, 13))
    var mutable = 1
    check(snapshot(mutable, run { mutable = 2; mutable }) == 12)
    check(mutable == 2)
    trace.order = 0
    try { throwing(trace); error("missing default exception") }
    catch (failure: IllegalArgumentException) { check(failure.message == "default") }
    check(trace.order == 12)
}
