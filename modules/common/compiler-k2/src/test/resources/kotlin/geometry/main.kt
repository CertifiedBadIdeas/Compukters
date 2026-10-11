/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import kotlin.math.*

fun near(actual: Float, expected: Float) {
    check(abs(actual - expected) <= 2e-6f) { "Expected $expected, got $actual" }
}

fun rejected(block: () -> Unit) {
    var failed = false
    try { block() } catch (error: IllegalArgumentException) { failed = true }
    check(failed) { "geometry assertion 16" }
}

value class CheckedVector(val x: Int, val y: Int) {
    init { if (x < 0) throw IllegalArgumentException("negative geometry component") }
}

fun verifyGeometry() {
    check(CheckedVector(1, 2).x == 1)
    rejected { CheckedVector(-1, 2) }
    val checkedConstructor: (Int, Int) -> CheckedVector = ::CheckedVector
    rejected { checkedConstructor(-1, 2) }
    val position: Position2i = 10 xy 20
    val size: Size2i = 80 by 24
    check(position + (2 xy 3) == Vector2i(12, 23)) { "geometry assertion 22" }
    check(position - (2 xy 3) == Vector2i(8, 17)) { "geometry assertion 23" }
    check(-position == Vector2i(-10, -20)) { "geometry assertion 24" }
    check(position * 2 == 2 * position && position * 2 == Vector2i(20, 40)) { "geometry assertion 25" }
    check((Vector2i(-7, 7) / 2) == Vector2i(-3, 3)) { "geometry assertion 26" }
    check((position dot (2 xy 3)) == 80) { "geometry assertion 27" }
    check((position cross (2 xy 3)) == -10) { "geometry assertion 28" }
    check((Vector2i(Int.MAX_VALUE, Int.MIN_VALUE) + Vector2i(1, -1)) == Vector2i(Int.MIN_VALUE, Int.MAX_VALUE)) { "geometry assertion 29" }
    check(Vector2i(3, 4).length == 5.0) { "geometry assertion 30" }
    check(Vector2i(Int.MIN_VALUE, 0).distanceTo(Vector2i(Int.MAX_VALUE, 0)) == 4294967295.0) { "geometry assertion 31" }
    check(position.toFloat() == Vector2f(10f, 20f)) { "geometry assertion 32" }

    val a: Position3i = Vector3i(1, 0, 0)
    val b: Size3i = Vector3i(0, 1, 0)
    check((a cross b) == Vector3i(0, 0, 1)) { "geometry assertion 36" }
    check((a dot b) == 0) { "geometry assertion 37" }
    check(a + b == Vector3i(1, 1, 0)) { "geometry assertion 38" }
    check(-(a - b) == Vector3i(-1, 1, 0)) { "geometry assertion 39" }
    check((3 * Vector3i(2, 4, 6)) / 2 == Vector3i(3, 6, 9)) { "geometry assertion 40" }
    check(Vector3i(2, 3, 6).length == 7.0) { "geometry assertion 41" }
    check(a.distanceTo(Vector3i(4, 4, 0)) == 5.0) { "geometry assertion 42" }
    check(a.toFloat() == Vector3f(1f, 0f, 0f)) { "geometry assertion 43" }

    val f: Position2f = 3f xy 4f
    near(f.length, 5f)
    near(f.normalized().x, 0.6f)
    near(f.normalized().y, 0.8f)
    check(Vector2f(0f, 0f).normalized() == Vector2f(0f, 0f)) { "geometry assertion 49" }
    near(Vector2f(Float.MAX_VALUE, Float.MAX_VALUE).normalized().length, 1f)
    near(Vector2f(Float.MIN_VALUE, 0f).normalized().x, 1f)
    near(f.distanceTo(0f xy 0f), 5f)
    check(f + f == f * 2f && 2f * f == f * 2f) { "geometry assertion 53" }
    check((f - f) == Vector2f(0f, 0f)) { "geometry assertion 54" }
    check((-f / 2f) == Vector2f(-1.5f, -2f)) { "geometry assertion 55" }
    near(f dot f, 25f)
    near(f cross (1f xy 0f), -4f)
    check(f.lerp(5f xy 8f, 0.5f) == Vector2f(4f, 6f)) { "geometry assertion 58" }
    check(f.lerp(5f xy 8f, 2f) == Vector2f(7f, 12f)) { "geometry assertion 59" }
    near(Vector2f(-Float.MAX_VALUE, 0f).lerp(Vector2f(Float.MAX_VALUE, 0f), 0.5f).x, 0f)
    check(Vector2f(-1.5f, 1.5f).roundToInt() == Vector2i(-1, 2)) { "geometry assertion 61" }
    check(Vector2f(-1.5f, 1.5f).floorToInt() == Vector2i(-2, 1)) { "geometry assertion 62" }
    check(Vector2f(-1.5f, 1.5f).ceilToInt() == Vector2i(-1, 2)) { "geometry assertion 63" }
    check(Vector2f(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY).ceilToInt() == Vector2i(Int.MIN_VALUE, Int.MAX_VALUE)) { "geometry assertion 64" }
    rejected { Vector2f(Float.NaN, 0f).roundToInt() }
    rejected { Vector2f(0f, Float.NaN).floorToInt() }
    rejected { Vector2f(Float.NaN, 0f).ceilToInt() }

    val u: Position3f = Vector3f(1f, 0f, 0f)
    val v: Size3f = Vector3f(0f, 1f, 0f)
    check((u cross v) == Vector3f(0f, 0f, 1f)) { "geometry assertion 71" }
    near(u dot v, 0f)
    check(u + v == Vector3f(1f, 1f, 0f)) { "geometry assertion 73" }
    check(-(u - v) == Vector3f(-1f, 1f, -0f)) { "geometry assertion 74" }
    check((3f * Vector3f(2f, 4f, 6f)) / 2f == Vector3f(3f, 6f, 9f)) { "geometry assertion 75" }
    near(Vector3f(2f, 3f, 6f).length, 7f)
    near(u.distanceTo(Vector3f(4f, 4f, 0f)), 5f)
    near(Vector3f(2f, 3f, 6f).normalized().length, 1f)
    check(Vector3f(0f, 0f, 0f).normalized() == Vector3f(0f, 0f, 0f)) { "geometry assertion 79" }
    near(Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE).normalized().length, 1f)
    check(u.lerp(v, 0.5f) == Vector3f(0.5f, 0.5f, 0f)) { "geometry assertion 81" }
    check(Vector3f(-1.5f, 1.5f, 0.5f).roundToInt() == Vector3i(-1, 2, 1)) { "geometry assertion 82" }
    check(Vector3f(-1.5f, 1.5f, 0.5f).floorToInt() == Vector3i(-2, 1, 0)) { "geometry assertion 83" }
    check(Vector3f(-1.5f, 1.5f, 0.5f).ceilToInt() == Vector3i(-1, 2, 1)) { "geometry assertion 84" }

    val rect = position sized size
    check(rect.left == 10 && rect.top == 20 && rect.right == 90 && rect.bottom == 44) { "geometry assertion 87" }
    check(position in rect && (89 xy 43) in rect) { "geometry assertion 88" }
    check((90 xy 20) !in rect && (10 xy 44) !in rect) { "geometry assertion 89" }
    val overlapping = (80 xy 30) sized (20 by 20)
    check(rect.intersects(overlapping)) { "geometry assertion 91" }
    check(rect.intersection(overlapping) == ((80 xy 30) sized (10 by 14))) { "geometry assertion 92" }
    check(rect.union(overlapping) == ((10 xy 20) sized (90 by 30))) { "geometry assertion 93" }
    check(rect.intersection((90 xy 20) sized (1 by 1)).isEmpty) { "geometry assertion 94" }
    check(!rect.intersects((90 xy 20) sized (1 by 1))) { "geometry assertion 95" }
    val empty = (1000 xy 1000) sized (0 by 2)
    check(empty in rect && empty.isEmpty && !empty.intersects(rect)) { "geometry assertion 97" }
    check(rect.union(empty) == rect && empty.union(rect) == rect) { "geometry assertion 98" }
    check(rect in rect) { "geometry assertion 99" }
    rejected { (0 xy 0) sized (-1 by 3) }
    rejected { (Int.MAX_VALUE xy 0) sized (1 by 1) }
    rejected { ((Int.MIN_VALUE xy 0) sized (1 by 1)).union((Int.MAX_VALUE - 1 xy 0) sized (1 by 1)) }
    check(((Int.MIN_VALUE xy 0) sized (1 by 1)).intersection((Int.MAX_VALUE - 1 xy 0) sized (1 by 1)).isEmpty) { "geometry assertion 103" }

    val rf = (1f xy 2f) sized (3f by 4f)
    val sf = (3f xy 4f) sized (3f by 4f)
    check((1f xy 2f) in rf && (4f xy 2f) !in rf) { "geometry assertion 107" }
    check(rf.intersects(sf)) { "geometry assertion 108" }
    check(rf.intersection(sf) == ((3f xy 4f) sized (1f by 2f))) { "geometry assertion 109" }
    check(rf.union(sf) == ((1f xy 2f) sized (5f by 6f))) { "geometry assertion 110" }
    check(rf in rf) { "geometry assertion 111" }
    val ef = (100f xy 100f) sized (0f by 0f)
    check(ef in rf && !ef.intersects(rf)) { "geometry assertion 113" }
    check(rf.union(ef) == rf && ef.union(rf) == rf) { "geometry assertion 114" }
    check(rf.intersection((4f xy 2f) sized (1f by 1f)).isEmpty) { "geometry assertion 115" }
    check(!rf.intersects((4f xy 2f) sized (1f by 1f))) { "geometry assertion 116" }
    rejected { (0f xy 0f) sized (Float.NaN by 1f) }
    rejected { (Float.POSITIVE_INFINITY xy 0f) sized (1f by 1f) }
    rejected { (Float.MAX_VALUE xy 0f) sized (Float.MAX_VALUE by 1f) }

    check(((Float.MAX_VALUE xy 0f) sized (1f by 1f)).isEmpty) { "Float extent collapse is empty" }
    val makeRect: (Vector2i, Vector2i) -> Rect2i = ::Rect2i
    rejected { makeRect(0 xy 0, -1 by 3) }

    val values = listOf<Any>(position, f, a, u, rect, rf)
    check(values[0] == position && values[1] == f && values[2] == a && values[3] == u) { "geometry assertion 122" }
    check(values[4] == rect && values[5] == rf) { "geometry assertion 123" }
    val aliases: Array<Size2i> = arrayOf(position, size)
    check(aliases[0] == position) { "geometry assertion 125" }
    val dimensions = mutableSetOf<Vector2i>(position, size, 10 xy 20)
    check(dimensions.size == 2) { "geometry assertion 127" }
    val make: (Int, Int) -> Vector2i = ::Vector2i
    check(make(10, 20) == position) { "geometry assertion 129" }
    verifyMatrices()
    println("geometry ok")
}

fun main() {
    try { verifyGeometry() }
    catch (error: Throwable) { println(error.message ?: "unknown geometry failure"); throw error }
}

fun verifyMatrices() {
    val identity = Matrix4f.identity()
    check(identity == Matrix4f.identity())
    val point = Vector3f(1f, 2f, 3f)
    check(identity * point == Vector4f(1f, 2f, 3f, 1f))
    val translation = Matrix4f.translation(Vector3f(10f, 20f, 30f))
    val scale = Matrix4f.scale(Vector3f(2f, 3f, 4f))
    check((translation * scale) * point == Vector4f(12f, 26f, 42f, 1f))
    check((scale * translation) * point == Vector4f(22f, 66f, 132f, 1f))
    check(translation.transformDirection(point) == Vector4f(1f, 2f, 3f, 0f))
    check(translation.transformPoint(point) == translation * point)
    check(Matrix4f.scale(2f) * point == Vector4f(2f, 4f, 6f, 1f))
    val quarterTurn = (PI / 2.0).toFloat()
    val rx = Matrix4f.rotationX(quarterTurn) * Vector3f(0f, 1f, 0f)
    near(rx.y, 0f); near(rx.z, 1f)
    val ry = Matrix4f.rotationY(quarterTurn) * Vector3f(0f, 0f, 1f)
    near(ry.x, 1f); near(ry.z, 0f)
    val rz = Matrix4f.rotationZ(quarterTurn) * Vector3f(1f, 0f, 0f)
    near(rz.x, 0f); near(rz.y, 1f)

    val dense = Matrix4f(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f, 11f, 12f, 13f, 14f, 15f, 16f)
    val other = Matrix4f(16f, 15f, 14f, 13f, 12f, 11f, 10f, 9f, 8f, 7f, 6f, 5f, 4f, 3f, 2f, 1f)
    val multiply: (Matrix4f) -> Matrix4f = dense::times
    val vectorMultiply: (Vector4f) -> Vector4f = dense::times
    val transpose: () -> Matrix4f = dense::transposed
    val direction: (Vector3f) -> Vector4f = translation::transformDirection
    check(multiply(other) == dense * other)
    check(vectorMultiply(Vector4f(1f, 2f, 3f, 4f)) == dense * Vector4f(1f, 2f, 3f, 4f))
    check(transpose() == dense.transposed())
    check(direction(point) == translation.transformDirection(point))
    val infiniteTranslation = Matrix4f(1f, 0f, 0f, Float.POSITIVE_INFINITY, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    val nanDirection = infiniteTranslation.transformDirection(point).x
    check(nanDirection != nanDirection)
    check(infiniteTranslation.transformPoint(point).x == Float.POSITIVE_INFINITY)
    val cancellation = Matrix4f(16777216f, 1f, -16777216f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    check((cancellation * Vector4f(1f, 1f, 1f, 1f)).x == 1f)
    val product = dense * other
    for (row in 0..3) {
        for (column in 0..3) {
            var sum = 0f
            for (k in 0..3) sum += dense[row, k] * other[k, column]
            check(product[row, column] == sum) { "dense multiplication $row $column" }
            check(dense.transposed()[row, column] == dense[column, row])
        }
    }
    check(dense * identity == dense && identity * dense == dense)
    check(dense.transposed().transposed() == dense)
    check(dense * Vector4f(1f, 2f, 3f, 4f) == Vector4f(30f, 70f, 110f, 150f))
    var badIndexCount = 0
    for (index in listOf(-1, 4, Int.MAX_VALUE)) {
        try { dense[index, 0] } catch (error: IndexOutOfBoundsException) { badIndexCount++ }
        try { dense[0, index] } catch (error: IndexOutOfBoundsException) { badIndexCount++ }
    }
    check(badIndexCount == 6)

    val vector = Vector4f(1f, 2f, 3f, 4f)
    check(vector.xyz == point && vector.length > 5f)
    check(vector + vector == 2f * vector && vector - vector == Vector4f(0f, 0f, 0f, 0f))
    check(-vector == Vector4f(-1f, -2f, -3f, -4f) && vector * 2f / 2f == vector)
    check((vector dot vector) == 30f)
    check(Vector4f(0f, -0f, 0f, 0f).normalized() == Vector4f(0f, 0f, 0f, 0f))
    near(Vector4f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE).normalized().w, 0.5f)
    near(vector.normalized().length, 1f)
    check(vector.perspectiveDivide() == Vector3f(0.25f, 0.5f, 0.75f))
    check(Vector4f(2f, 4f, 6f, -2f).perspectiveDivide() == Vector3f(-1f, -2f, -3f))
    for (w in listOf(0f, -0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
        rejected { Vector4f(1f, 2f, 3f, w).perspectiveDivide() }
        check(!Vector4f(0f, 0f, 0f, w).isInsideClipVolume)
    }
    check(Vector4f(-2f, 2f, -2f, 2f).isInsideClipVolume)
    check(!Vector4f(2.01f, 0f, 0f, 2f).isInsideClipVolume)
    check(!Vector4f(0f, Float.NaN, 0f, 2f).isInsideClipVolume)
    check(!Vector4f(0f, 0f, Float.POSITIVE_INFINITY, 2f).isInsideClipVolume)
    check(!Vector4f(0f, 0f, 0f, -2f).isInsideClipVolume)

    val projection = Matrix4f.perspective(quarterTurn, 2f, 1f, 9f)
    val nearPoint = projection * Vector3f(0f, 0f, -1f)
    val farPoint = projection * Vector3f(0f, 0f, -9f)
    near(nearPoint.w, 1f); near(nearPoint.perspectiveDivide().z, -1f)
    near(farPoint.w, 9f); near(farPoint.perspectiveDivide().z, 1f)
    check(nearPoint.isInsideClipVolume && farPoint.isInsideClipVolume)
    val corner = projection * Vector3f(2f, 1f, -1f)
    near(corner.perspectiveDivide().x, 1f); near(corner.perspectiveDivide().y, 1f)
    check(!(projection * Vector3f(0f, 0f, 1f)).isInsideClipVolume)
    check(!(projection * Vector3f(0f, 0f, -0.5f)).isInsideClipVolume)
    check(!(projection * Vector3f(0f, 0f, -10f)).isInsideClipVolume)
    val ortho = Matrix4f.orthographic(-2f, 6f, -1f, 3f, 1f, 9f)
    check((ortho * Vector3f(-2f, -1f, -1f)).perspectiveDivide() == Vector3f(-1f, -1f, -1f))
    check((ortho * Vector3f(6f, 3f, -9f)).perspectiveDivide() == Vector3f(1f, 1f, 1f))
    check((ortho * Vector3f(2f, 1f, -5f)).perspectiveDivide() == Vector3f(0f, 0f, 0f))
    val wide = Matrix4f.orthographic(-Float.MAX_VALUE, Float.MAX_VALUE, -1f, 1f, -Float.MAX_VALUE, Float.MAX_VALUE)
    check(wide.m00 > 0f && wide.m22 < 0f && wide.m23 == 0f)
    val huge = Matrix4f.perspective(quarterTurn, 1f, Float.MAX_VALUE / 4f, Float.MAX_VALUE / 2f)
    check(abs(huge.m22) <= Float.MAX_VALUE && abs(huge.m23) <= Float.MAX_VALUE)
    for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
        rejected { Matrix4f.perspective(quarterTurn, bad, 1f, 9f) }
        rejected { Matrix4f.perspective(bad, 1f, 1f, 9f) }
        rejected { Matrix4f.perspective(quarterTurn, 1f, bad, 9f) }
    }
    rejected { Matrix4f.perspective(PI.toFloat(), 1f, 1f, 9f) }
    rejected { Matrix4f.perspective(quarterTurn, 1f, 9f, 9f) }
    rejected { Matrix4f.perspective(quarterTurn, 1f, 1f, Float.NaN) }
    rejected { Matrix4f.perspective(quarterTurn, Float.MIN_VALUE, 1f, 9f) }
    rejected { Matrix4f.orthographic(0f, 0f, -1f, 1f, 1f, 9f) }
    rejected { Matrix4f.orthographic(-1f, 1f, 1f, -1f, 1f, 9f) }
    rejected { Matrix4f.orthographic(-1f, 1f, -1f, 1f, 9f, 1f) }
    rejected { Matrix4f.orthographic(-1f, Float.POSITIVE_INFINITY, -1f, 1f, 1f, 9f) }
    rejected { Matrix4f.orthographic(0f, Float.MIN_VALUE, -1f, 1f, 1f, 9f) }

    val values = listOf<Any>(identity, dense, vector)
    check(values[0] == Matrix4f.identity() && values[1] == dense && values[2] == vector)
    check(values[0] != vector)
    check(mutableSetOf(identity, Matrix4f.identity(), dense).size == 2)
    check(mutableSetOf(vector, Vector4f(1f, 2f, 3f, 4f)).size == 1)
    val transform: (Vector3f) -> Vector4f = translation::transformPoint
    check(transform(point) == translation * point)
    val makeProjection: (Float, Float, Float, Float) -> Matrix4f = Matrix4f::perspective
    check(makeProjection(quarterTurn, 2f, 1f, 9f) == projection)
}
