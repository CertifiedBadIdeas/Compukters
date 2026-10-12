/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

class Controller {
    var onUpdate: (() -> Unit)? = null
    fun update() { onUpdate?.invoke() }
}

class Holder<T>(var callback: ((T) -> T)? = null)
class Box(val value: Int)
class Accessor(initial: (() -> Int)?) {
    var changes = 0
    var reads = 0
    var callback: (() -> Int)? = initial
        get() { reads += 1; return field }
        set(value) { changes += 1; field = value }
}

interface Switch { var callback: (() -> Int)? }
class SwitchImpl : Switch { override var callback: (() -> Int)? = null }
class GenericDefaults<T>(val first: ((T) -> T)?, var second: ((T) -> T)? = first)

fun absentUnit(): Unit? = null
fun presentUnit(): Unit? = Unit
fun isUnit(value: Any?): Boolean = value === presentUnit()

fun optional(present: Boolean): (() -> Int)? = if (present) { { 7 } } else null
fun apply(callback: ((Int) -> Int)? = null, value: Int = 4): Int = callback?.invoke(value) ?: -1
fun <T> wrap(callback: ((T) -> T)?): ((T) -> T)? = callback
fun <T> make(value: T): (() -> T)? {
    fun read(): T = value
    return ::read
}

fun <T> safe(value: T, callback: ((T) -> T)?): T = callback?.invoke(value) ?: value
fun <T> once(value: T, callback: ((T) -> T)?): () -> T {
    var action = callback
    fun tick(): T {
        val saved = action
        action = null
        return saved?.invoke(value) ?: value
    }
    return ::tick
}

fun allocate(seed: Int): () -> Int {
    var data = ByteArray(1)
    val callback = { data[0].toInt() }
    data = ByteArray(12 * 1024)
    data[0] = seed.toByte()
    return callback
}

fun pressure() {
    val holder = Holder<Int>()
    holder.callback = { it + allocate(11)() }
    holder.callback = null
    val retained = allocate(17)
    val store = Accessor(retained)
    val alias = store.callback
    store.callback = null
    var index = 0
    while (index < 200) {
        store.callback = allocate(index % 100)
        check(store.callback?.invoke() == index % 100)
        store.callback = null
        index += 1
    }
    check(alias?.invoke() == 17)
    check(store.callback == null)
}

fun main() {
    var updates = 0
    val controller = Controller()
    controller.update()
    controller.onUpdate = { updates += 1 }
    controller.update()
    controller.onUpdate = null
    controller.update()
    check(updates == 1)

    var unit: (() -> Unit)? = null
    check(unit?.invoke() == null)
    unit = { updates += 1 }
    check(unit?.invoke() != null && updates == 2)
    val nullUnit: (() -> Unit?)? = ::absentUnit
    check(nullUnit?.invoke() == null)
    val presentUnit: (() -> Unit?)? = ::presentUnit
    check(presentUnit?.invoke() != null)
    val unitResult: Unit? = presentUnit?.invoke()
    val narrowedBox: Any = unitResult!!
    check(isUnit(narrowedBox))
    val fallbackUnit: Unit? = nullUnit?.invoke() ?: Unit
    check(isUnit(fallbackUnit))
    var unitFailure = false
    try { nullUnit?.invoke()!! } catch (e: NullPointerException) { unitFailure = true }
    check(unitFailure)

    var arguments = 0
    var receivers = 0
    var call: ((Int) -> Int)? = null
    fun argument(): Int { arguments += 1; return 5 }
    fun receiver(): ((Int) -> Int)? { receivers += 1; return call }
    check(receiver()?.invoke(argument()) == null)
    check(arguments == 0 && receivers == 1)
    call = { it + 1 }
    check(receiver()?.invoke(argument()) == 6)
    check(arguments == 1 && receivers == 2)
    val old = call
    call = null
    check(old?.invoke(7) == 8)
    check(call?.invoke(argument()) == null)
    check(arguments == 1)

    var fallbacks = 0
    fun fallback(): () -> Int { fallbacks += 1; return { 9 } }
    val chosen = optional(false) ?: fallback()
    check(chosen() == 9 && fallbacks == 1)
    val present = optional(true) ?: fallback()
    check(present() == 7 && fallbacks == 1)
    val smart = optional(true)
    if (smart != null) check(smart() == 7) else error("missing callback")
    check(optional(true)!!() == 7)
    var caught = false
    try { optional(false)!!() } catch (failure: NullPointerException) { caught = true }
    check(caught)
    check(apply() == -1)
    check(apply({ it * 2 }) == 8)
    check(wrap<Int>(null) == null)
    check(wrap<Int> { it + 3 }?.invoke(4) == 7)
    check(make(12)?.invoke() == 12)
    check(make(13L)?.invoke() == 13L)

    val holder = Holder<Int>()
    holder.callback = { it + 2 }
    check(holder.callback?.invoke(3) == 5)
    holder.callback = null
    check(holder.callback?.invoke(3) == null)
    val box = Box(21)
    val refs = Holder<Box> { it }
    check(refs.callback?.invoke(box) === box)
    refs.callback = null
    check(refs.callback == null)
    val access = Accessor { 23 }
    val alias = access.callback
    access.callback = null
    check(access.changes == 1 && alias?.invoke() == 23)
    check(access.callback?.invoke() == null && access.reads == 2)
    val via: Switch = SwitchImpl()
    via.callback = { 41 }
    check(via.callback?.invoke() == 41)
    via.callback = null
    check(via.callback?.invoke() == null)
    val defaults = GenericDefaults<Int>({ it + 1 })
    check(defaults.first === defaults.second)
    check(defaults.second?.invoke(2) == 3)
    defaults.second = null
    check(defaults.second == null)

    var shared: (() -> Int)? = null
    fun read(): Int = shared?.invoke() ?: -1
    val reader = ::read
    check(reader() == -1)
    shared = { 29 }
    check(reader() == 29)
    shared = null
    check(reader() == -1)

    val one = once(3) { it + 1 }
    check(one() == 4 && one() == 3)
    val wide = once(5L) { it + 2L }
    check(wide() == 7L && wide() == 5L)
    val otherBox = Box(31)
    val refsOnce = once(box) { otherBox }
    check(refsOnce() === otherBox && refsOnce() === box)
    check(safe(3, null) == 3)
    check(safe(true) { !it } == false)
    check(safe(1.toByte()) { (it + 1).toByte() } == 2.toByte())
    check(safe(2.toShort()) { (it + 1).toShort() } == 3.toShort())
    check(safe(3) { it + 1 } == 4)
    check(safe(4L) { it + 1L } == 5L)
    check(safe(5.toUByte()) { (it + 1u).toUByte() } == 6.toUByte())
    check(safe(6.toUShort()) { (it + 1u).toUShort() } == 7.toUShort())
    check(safe(7u) { it + 1u } == 8u)
    check(safe(8uL) { it + 1uL } == 9uL)
    check(safe(1.5f) { it + 0.5f } == 2.0f)
    check(safe(2.5) { it + 0.5 } == 3.0)
    check(safe('a') { it + 1 } == 'b')

    val outer: (() -> (() -> Int)?)? = { optional(true) }
    check(outer?.invoke()?.invoke() == 7)
    pressure()
}
