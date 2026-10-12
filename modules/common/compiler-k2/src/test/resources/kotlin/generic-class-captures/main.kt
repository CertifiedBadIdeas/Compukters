/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

value class Point(val x: Int, val y: Long)
class Box(val value: Int)
interface Reading<T> { fun reader(): () -> T }
class Holder<T>(var value: T) : Reading<T> {
    override fun reader(): () -> T {
        fun read(): T = value
        return ::read
    }
    fun snapshot(seed: T = value): () -> T = { seed }
    fun cell(seed: T, next: T): () -> T {
        var local = seed
        fun take(): T {
            val saved = local
            local = next
            return saved
        }
        return ::take
    }
    fun direct(seed: T): T {
        fun <R> identity(value: R): R = value
        fun read(fallback: T = seed): T = identity(fallback)
        return read()
    }
    fun recursive(depth: Int): () -> T {
        if (depth > 0) return recursive(depth - 1)
        return reader()
    }
    val readProperty: () -> T
        get() {
            fun read(): T = value
            return ::read
        }
    fun shared(seed: T, next: T): () -> T {
        var local = seed
        callback = { local }
        return { val saved = local; local = next; saved }
    }
    fun install() { callback = reader() }
    var callback: (() -> T)? = null
}

fun retainedArray(seed: Int): () -> ByteArray {
    val data = ByteArray(12 * 1024)
    data[0] = seed.toByte()
    return Holder(data).reader()
}

fun pressure() {
    val retained = retainedArray(17)
    var current: (() -> ByteArray)? = null
    var index = 0
    while (index < 200) {
        current = retainedArray(index % 100)
        check(current?.invoke()?.get(0) == (index % 100).toByte())
        current = null
        index += 1
    }
    check(current == null && retained()[0] == 17.toByte())
}

fun main() {
    val ints = Holder(3)
    val read = ints.reader()
    val view: Reading<Int> = ints
    check(view.reader()() == 3)
    val factory = ints::reader
    check(factory()() == 3)
    val unbound: (Holder<Int>) -> (() -> Int) = Holder<Int>::reader
    check(unbound(ints)() == 3)
    check(ints.recursive(3)() == 3)
    val propertyRead = ints.readProperty
    val snapshot = ints.snapshot()
    check(read() == 3 && snapshot() == 3)
    ints.value = 7
    check(read() == 7 && snapshot() == 3 && propertyRead() == 7)
    val shared = ints.shared(61, 67)
    check(ints.callback?.invoke() == 61)
    check(shared() == 61 && ints.callback?.invoke() == 67)
    val cell = ints.cell(11, 13)
    val otherCell = ints.cell(17, 19)
    check(cell() == 11 && cell() == 13)
    check(otherCell() == 17 && otherCell() == 19)
    check(ints.direct(23) == 23)
    ints.install()
    val alias = ints.callback
    ints.callback = null
    ints.value = 29
    check(alias?.invoke() == 29)
    val longs = Holder(31L)
    val wide = longs.reader()
    longs.value = 37L
    check(wide() == 37L && longs.cell(41L, 43L)() == 41L)
    val box = Box(47)
    val refs = Holder(box)
    val ref = refs.reader()
    val next = Box(53)
    refs.value = next
    check(ref() === next && refs.snapshot(box)() === box)
    val nullable = Holder<Int?>(null)
    val nullableRead = nullable.reader()
    check(nullableRead() == null)
    nullable.value = 59
    check(nullableRead() == 59)
    check(Holder(true).reader()())
    check(Holder(1.toByte()).reader()() == 1.toByte())
    check(Holder(2.toShort()).reader()() == 2.toShort())
    check(Holder(3).reader()() == 3)
    check(Holder(4L).reader()() == 4L)
    check(Holder(5.toUByte()).reader()() == 5.toUByte())
    check(Holder(6.toUShort()).reader()() == 6.toUShort())
    check(Holder(7u).reader()() == 7u)
    check(Holder(8uL).reader()() == 8uL)
    check(Holder(1.5f).reader()() == 1.5f)
    check(Holder(2.5).reader()() == 2.5)
    check(Holder('a').reader()() == 'a')
    val point = Point(73, 79L)
    val points = Holder(point)
    val pointRead = points.reader()
    val pointCell = points.cell(point, Point(83, 89L))
    check(pointCell().x == 73 && pointCell().y == 89L)
    points.value = Point(97, 101L)
    check(pointRead().x == 97 && pointRead().y == 101L)
    check(points.snapshot(point)().y == 79L)
    val nested = Holder<(() -> Int)?>(null)
    val nestedRead = nested.reader()
    check(nestedRead() == null)
    nested.value = { 71 }
    check(nestedRead()?.invoke() == 71)
    pressure()
}
