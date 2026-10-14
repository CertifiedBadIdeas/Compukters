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
open class Parent<A, B>(var first: A, var second: B) {
    fun <R> keep(seed: R): R = seed
    fun <R> map(transform: (A, B) -> R): R = transform(first, second)
    fun <R> reader(transform: (A, B) -> R): () -> R = { transform(first, second) }
    fun <R> cell(seed: R, next: R): () -> R {
        var current = seed
        fun take(): R { val saved = current; current = next; return saved }
        return ::take
    }
    fun <R> defaulted(seed: R, copy: R = seed, owner: A = first): R {
        check(owner == first)
        return copy
    }
    fun <R> callback(seed: R, take: () -> R = { seed }): () -> R = take
    fun <R> repeat(seed: R, depth: Int): R = if (depth == 0) seed else repeat(seed, depth - 1)
    fun <R> ordered(seed: R, token: Int = mark(4)): R { check(token == 4); return seed }
    open fun label(): A = first
}
open class Middle<X, Y>(first: Y, second: List<X>) : Parent<Y, List<X>>(first, second)
class Leaf<T>(first: String, second: List<T>) : Middle<T, String>(first, second) {
    val initializedReader: () -> String = { first }
    fun throughSuper(): Long = super.keep(31L)
    override fun label(): String = first
}
class OtherLeaf : Parent<String, List<Int>>("other", listOf(8))
open class ScalarParent<T>(var stored: T) {
    fun <R> read(transform: (T) -> R): R = transform(stored)
}
class ScalarChild<T>(value: T) : ScalarParent<T>(value) {
    val initial: () -> T = { value }
    val live: () -> T = { stored }
}
fun inheritedReceiver(): Leaf<Int> { mark(1); return Leaf("ordered", listOf(1)) }
fun <T> inheritedStorage(seed: T, next: T) {
    val child = ScalarChild(seed)
    val base: ScalarParent<T> = child
    check(child.stored == seed && child.initial() == seed)
    child.stored = next
    check(base.read { it } == next && child.live() == next && child.initial() == seed)
}
fun inheritedMethods() {
    inheritedStorage(false, true)
    inheritedStorage('a', 'b')
    inheritedStorage(1.toByte(), 2.toByte())
    inheritedStorage(3.toShort(), 4.toShort())
    inheritedStorage(5, 6)
    inheritedStorage(7L, 8L)
    inheritedStorage(9.toUByte(), 10.toUByte())
    inheritedStorage(11.toUShort(), 12.toUShort())
    inheritedStorage(13u, 14u)
    inheritedStorage(15uL, 16uL)
    inheritedStorage(1.5f, 2.5f)
    inheritedStorage(3.5, 4.5)
    inheritedStorage("first", "next")
    inheritedStorage<Int?>(null, 17)
    inheritedStorage(Point(19, 23L), Point(29, 31L))
    val scalar = ScalarChild(17)
    check(scalar.stored == 17 && scalar.read { it.toLong() } == 17L)
    scalar.stored = 19
    check(scalar.live() == 19 && scalar.initial() == 17)
    val nullable = ScalarChild<Int?>(null)
    check(nullable.live() == null)
    nullable.stored = 23
    check(nullable.read { it ?: 0 } == 23 && nullable.initial() == null)
    val leaf = Leaf("first", listOf(1, 2))
    check(leaf.map { text, values -> text.length + values.size } == 7)
    val reader = leaf.reader { text, values -> text.length + values.size }
    leaf.first = "changed"
    leaf.second = listOf(3)
    check(reader() == 8 && leaf.initializedReader() == "first")
    check(leaf.label() == "changed")
    val base: Parent<String, List<Int>> = leaf
    check(base === leaf && base.label() == "changed")
    check(base.keep(19) == 19 && OtherLeaf().keep(23) == 23)
    check(leaf.keep(true) && leaf.keep('z') == 'z')
    check(leaf.keep(1.toByte()) == 1.toByte() && leaf.keep(2.toShort()) == 2.toShort())
    check(leaf.keep(3) == 3 && leaf.keep(4L) == 4L)
    check(leaf.keep(5.toUByte()) == 5.toUByte() && leaf.keep(6.toUShort()) == 6.toUShort())
    check(leaf.keep(7u) == 7u && leaf.keep(8uL) == 8uL)
    check(leaf.keep(1.5f) == 1.5f && leaf.keep(2.5) == 2.5)
    check(leaf.keep<Int?>(null) == null && leaf.keep("reference") == "reference")
    val point = Point(11, 13L)
    check(leaf.keep(point).y == 13L && leaf.callback(point)().x == 11)
    check(leaf.defaulted<Int?>(null) == null && leaf.defaulted(29L) == 29L)
    check(leaf.repeat("recursive", 4) == "recursive" && leaf.throughSuper() == 31L)
    val cell = leaf.cell(37L, 41L)
    val other = leaf.cell(43L, 47L)
    check(cell() == 37L && cell() == 41L && other() == 43L && other() == 47L)
    Order.value = 0
    check(inheritedReceiver().ordered(mark(2)) == 2 && Order.value == 124)
    check(Leaf("nested", listOf(listOf(1L))).map { _, values -> values[0][0] } == 1L)
}
fun main() {
    inheritedMethods()
    Order.value = 0
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
