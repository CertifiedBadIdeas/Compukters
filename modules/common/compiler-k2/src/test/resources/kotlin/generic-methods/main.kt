/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

value class Point(val x: Int, val y: Long)
class Box<T>(var value: T) {
    fun <R> map(transform: (T) -> R): Box<R> = Box(transform(value))
    fun <R> cell(seed: R, next: R): () -> R {
        var local = seed
        fun <S> keep(value: S): S = value
        fun take(): R { val old = local; local = next; return keep(old) }
        return ::take
    }
    fun <R> reader(transform: (T) -> R): () -> R = { transform(value) }
    fun <R> repeat(value: R, depth: Int): R =
        if (depth == 0) value else repeat(value, depth - 1)
    fun <R> defaulted(seed: R, copy: R = seed, owner: T = value): R {
        check(owner == value)
        return copy
    }
    fun <R> computed(seed: R, copy: R = Identity().keep(seed)): R = copy
    fun <R> defaultCallback(seed: R, take: () -> R = { seed }): () -> R = take
}
class Identity {
    fun <T> keep(value: T): T = value
    fun <T> pair(first: T, second: T): T = first
}
object Order {
    var value: Int = 0
    fun <T> keep(value: T): T = value
}
fun mark(value: Int): Int { Order.value = Order.value * 10 + value; return value }
fun receiver(): Identity { mark(1); return Identity() }
fun main() {
    val id = Identity()
    check(id.keep(true))
    check(id.keep(1.toByte()) == 1.toByte())
    check(id.keep(2.toShort()) == 2.toShort())
    check(id.keep(3) == 3)
    check(id.keep(4L) == 4L)
    check(id.keep(5.toUByte()) == 5.toUByte())
    check(id.keep(6.toUShort()) == 6.toUShort())
    check(id.keep(7u) == 7u)
    check(id.keep(8uL) == 8uL)
    check(id.keep(1.5f) == 1.5f)
    check(id.keep(2.5) == 2.5)
    check(id.keep('a') == 'a')
    check(id.keep<Int?>(null) == null)
    check(id.keep("hello") == "hello")
    val point = Point(11, 13L)
    check(id.keep(point).y == 13L)
    val box = Box(17)
    val mapped = box.map { it.toLong() }
    check(mapped.value == 17L)
    val reader = box.reader { it + 1 }
    box.value = 19
    check(reader() == 20)
    val cell = box.cell(23L, 29L)
    val other = box.cell(31L, 37L)
    check(cell() == 23L && cell() == 29L)
    check(other() == 31L && other() == 37L)
    check(box.repeat("recursive", 4) == "recursive")
    check(box.defaulted(41L) == 41L)
    check(box.computed(42L) == 42L)
    check(Box("string owner").map { it.length }.value == 12)
    check(Order.keep(point).x == 11)
    receiver().pair(second = mark(2), first = mark(3))
    check(Order.value == 123)
    check(box.defaultCallback(point)().x == 11)
    check(Box("owner").defaulted<Int?>(null) == null)
    var arg = 43
    check(id.pair(arg, run { arg = 47; arg }) == 43)
    val retained = box.cell(ByteArray(12 * 1024), ByteArray(1))
    var index = 0
    while (index < 200) {
        val bytes = ByteArray(12 * 1024)
        bytes[0] = (index % 100).toByte()
        check(box.defaultCallback(bytes)()[0] == (index % 100).toByte())
        index += 1
    }
    check(retained().size == 12 * 1024 && retained().size == 1)
}
