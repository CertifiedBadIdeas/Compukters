/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import kotlin.random.Random

class Trace {
    var calls: Int = 0
    var defaults: Int = 0
    fun mark(): Int { defaults += 1; return defaults }
    fun receiver(): Base { calls += 1; return Derived(this) }
}
fun defaults(first: Int = 2, second: Int = first + 3): Int = first * 10 + second
fun fresh(values: IntArray = intArrayOf(1)): IntArray = values
fun wide(value: Long = 0x123456789L): Long = value
fun nullable(value: String? = null): String? = value
value class Id(val number: Int) { fun read(value: Int = number): Int = number + value }
fun identity(value: Id = Id(9)): Id = value
open class Base(val trace: Trace) {
    open fun read(first: Int = trace.mark(), second: Int = first + 2): Int = first * 10 + second
}
class Derived(trace: Trace) : Base(trace) {
    override fun read(first: Int, second: Int): Int = first * 100 + second
}
interface Reader { fun read(first: Int = 3, second: Int = first + 4): Int }
class ReaderImpl : Reader { override fun read(first: Int, second: Int): Int = first * 10 + second }
class Generic<T>(val value: T) { fun read(fallback: T = value): T = fallback }
fun retain(trace: Trace): () -> Int = trace.receiver()::read
fun action(trace: Trace, value: Int = trace.mark()) { trace.calls += value }
fun fail(trace: Trace): Int { trace.defaults += 1; throw IllegalArgumentException("default") }
fun throwing(trace: Trace, value: Int = fail(trace)): Int { trace.calls += 1; return value }

fun main() {
    val zero: () -> Int = ::defaults
    val partial: (Int) -> Int = ::defaults
    check(zero() == 25)
    check(partial(7) == 80)
    val full: (Int, Int) -> Int = ::defaults
    check(full(4, 9) == 49)
    val array: () -> IntArray = ::fresh
    val one = array()
    one[0] = 99
    check(array()[0] == 1)
    val long: () -> Long = ::wide
    val absent: () -> String? = ::nullable
    val id: () -> Id = ::identity
    check(long() == 0x123456789L)
    check(absent() == null)
    check(id().number == 9)
    val fill: (ByteArray) -> ByteArray = Random(0)::nextBytes
    val expected = Random(0)
    val short = ByteArray(2)
    check(fill(short) === short)
    val shortExpected = expected.nextBytes(2)
    check(short[0] == shortExpected[0] && short[1] == shortExpected[1])
    val longer = ByteArray(5)
    check(fill(longer) === longer)
    val longerExpected = expected.nextBytes(5)
    for (index in 0 until longer.size) check(longer[index] == longerExpected[index])
    val valueMember: () -> Int = Id(3)::read
    val valueUnbound: (Id) -> Int = Id::read
    check(valueMember() == 6)
    check(valueUnbound(Id(5)) == 10)
    val action: (Trace) -> Unit = ::action
    val actionTrace = Trace()
    action(actionTrace)
    action(actionTrace)
    check(actionTrace.defaults == 2 && actionTrace.calls == 3)
    val trace = Trace()
    val bound: () -> Int = retain(trace)
    check(trace.calls == 1)
    check(trace.defaults == 0)
    check(bound() == 103)
    check(bound() == 204)
    check(trace.calls == 1)
    check(trace.defaults == 2)
    val withFirst: (Int) -> Int = trace.receiver()::read
    check(withFirst(8) == 810)
    check(trace.calls == 2)
    check(trace.defaults == 2)
    val unbound: (Base) -> Int = Base::read
    check(unbound(Derived(trace)) == 305)
    val unboundPartial: (Base, Int) -> Int = Base::read
    check(unboundPartial(Derived(trace), 4) == 406)
    val inherited: () -> Int = Derived(Trace())::read
    check(inherited() == 103)
    val inheritedUnbound: (Derived) -> Int = Derived::read
    check(inheritedUnbound(Derived(Trace())) == 103)
    val inheritedInterface: () -> Int = ReaderImpl()::read
    check(inheritedInterface() == 37)
    val iface: Reader = ReaderImpl()
    val reader: () -> Int = iface::read
    check(reader() == 37)
    val ifaceUnbound: (Reader) -> Int = Reader::read
    check(ifaceUnbound(iface) == 37)
    val generic: () -> String = Generic("value")::read
    check(generic() == "value")
    val failing: (Trace) -> Int = ::throwing
    try { failing(trace); error("missing exception") }
    catch (failure: IllegalArgumentException) { check(failure.message == "default") }
    check(trace.defaults == 4)
    check(trace.calls == 2)
}
