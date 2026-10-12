/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

class Counter(val next: () -> Int, val read: () -> Int)
fun counter(seed: Int): Counter {
    var count = seed
    fun next(): Int { count += 1; return count }
    fun read(): Int = count
    return Counter(::next, ::read)
}
class Mutable(var callback: (Int) -> Int)
class Body(seed: Int) {
    val callback: () -> Int = { seed + 1 }
}
class Accessor(initial: () -> Int) {
    var updates: Int = 0
    var callback: () -> Int = initial
        get() = field
        set(value) { updates += 1; field = value }
}
interface Handler { val callback: (Int) -> Int }
open class Base(override open val callback: (Int) -> Int) : Handler
class Derived(override val callback: (Int) -> Int) : Base({ 0 })
class Generic<T>(var callback: (T) -> T)
class Box(var value: Int)
class Defaults(seed: Int = 20, val callback: () -> Int = { seed })
class Factory(val create: () -> (() -> Int))
class Action(var run: () -> Unit)
class Optional(val callback: (String?) -> String?)

fun main() {
    val first = counter(0)
    val second = counter(10)
    check(first.next() == 1 && first.read() == 1)
    val alias = first
    check(alias.next() == 2 && first.read() == 2)
    check(second.next() == 11 && first.read() == 2)
    val mutable = Mutable { it + 1 }
    val old = mutable.callback
    mutable.callback = { it * 2 }
    check(old(3) == 4 && mutable.callback(3) == 6)
    var reads = 0
    fun owner(): Mutable { reads += 1; return mutable }
    check(owner().callback(4) == 8 && reads == 1)
    check(Body(30).callback() == 31)
    val accessor = Accessor(first.read)
    check(accessor.callback() == 2)
    accessor.callback = second.read
    check(accessor.callback() == 11 && accessor.updates == 1)
    val base: Base = Derived { it + 7 }
    val handler: Handler = base
    check(base.callback(3) == 10 && handler.callback(4) == 11)
    val ints = Generic<Int> { it + 3 }
    val longs = Generic<Long> { it + 0x100000000L }
    val objects = Generic<Box> { it.value += 1; it }
    check(ints.callback(4) == 7)
    ints.callback = { it * 4 }
    check(ints.callback(3) == 12)
    check(longs.callback(2L) == 0x100000002L)
    val box = Box(5)
    check(objects.callback(box) === box && box.value == 6)
    check(Defaults().callback() == 20 && Defaults(37).callback() == 37)
    val factory = Factory { first.next }
    check(factory.create()() == 3 && first.read() == 3)
    var events = 0
    val action = Action { events += 1 }
    action.run()
    action.run = { events += 10 }
    action.run()
    check(events == 11)
    val optional = Optional { if (it == null) "absent" else it }
    check(optional.callback(null) == "absent" && optional.callback("ok") == "ok")
}
