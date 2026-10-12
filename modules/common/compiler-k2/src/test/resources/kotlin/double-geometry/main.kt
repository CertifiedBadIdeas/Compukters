/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import kotlin.math.*

fun rejected(block: () -> Unit) {
    var failed = false
    try { block() } catch (error: IllegalArgumentException) { failed = true }
    expect(failed, 1)
}

fun main() {
    try { verifyDoubleVectors(); verifyRotations(); println("double geometry ok") }
    catch (error: Throwable) { println(error.message ?: "unknown Double geometry failure"); throw error }
}

fun expect(condition: Boolean, assertion: Int) {
    check(condition) { "Double geometry assertion $assertion" }
}

fun nearDouble(actual: Double, expected: Double) {
    check(abs(actual - expected) <= 2e-15) { "Expected $expected, got $actual" }
}

fun verifyDoubleVectors() {
    val pair = Vector2d(3.0, 4.0)
    expect(pair.length == 5.0 && pair.distanceTo(Vector2d(0.0, 0.0)) == 5.0, 2)
    nearDouble(pair.normalized().x, 0.6)
    nearDouble(pair.normalized().y, 0.8)
    expect(pair + pair == 2.0 * pair && -pair / 2.0 == Vector2d(-1.5, -2.0), 3)
    expect(pair - pair == Vector2d(0.0, 0.0), 4)
    expect((pair dot pair) == 25.0 && (pair cross Vector2d(1.0, 0.0)) == -4.0, 5)
    expect(pair.lerp(Vector2d(5.0, 8.0), 0.5) == Vector2d(4.0, 6.0), 6)
    expect(pair.lerp(Vector2d(5.0, 8.0), 2.0) == Vector2d(7.0, 12.0), 7)
    expect(Vector2d(-1.5, 1.5).roundToInt() == Vector2i(-1, 2), 8)
    expect(Vector2d(-1.5, 1.5).floorToInt() == Vector2i(-2, 1), 9)
    expect(Vector2d(-1.5, 1.5).ceilToInt() == Vector2i(-1, 2), 10)
    expect(Vector2d(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY).ceilToInt() == Vector2i(Int.MIN_VALUE, Int.MAX_VALUE), 11)
    rejected { Vector2d(Double.NaN, 0.0).roundToInt() }
    rejected { Vector2d(Double.NaN, 0.0).floorToInt() }
    rejected { Vector2d(0.0, Double.NaN).ceilToInt() }
    val axis = Vector3d(1.0, 0.0, 0.0)
    val up = Vector3d(0.0, 1.0, 0.0)
    expect((axis cross up) == Vector3d(0.0, 0.0, 1.0) && (axis dot up) == 0.0, 12)
    expect(Vector3d(2.0, 3.0, 6.0).length == 7.0, 13)
    expect(axis.distanceTo(Vector3d(4.0, 4.0, 0.0)) == 5.0, 14)
    expect(axis + up == Vector3d(1.0, 1.0, 0.0) && axis - up == Vector3d(1.0, -1.0, 0.0), 15)
    expect(-axis == Vector3d(-1.0, -0.0, -0.0) && 2.0 * axis / 2.0 == axis, 16)
    expect(axis.lerp(up, 0.5) == Vector3d(0.5, 0.5, 0.0), 17)
    expect(Vector3d(-1.5, 1.5, 2.5).roundToInt() == Vector3i(-1, 2, 3), 18)
    expect(Vector3d(-1.5, 1.5, 2.5).floorToInt() == Vector3i(-2, 1, 2), 19)
    expect(Vector3d(-1.5, 1.5, 2.5).ceilToInt() == Vector3i(-1, 2, 3), 20)
    val homogeneous = Vector4d(1.0, 2.0, 3.0, 4.0)
    expect(homogeneous.xyz == Vector3d(1.0, 2.0, 3.0), 21)
    expect(homogeneous + homogeneous == 2.0 * homogeneous && homogeneous - homogeneous == Vector4d(0.0, 0.0, 0.0, 0.0), 22)
    expect(-homogeneous / -1.0 == homogeneous && (homogeneous dot homogeneous) == 30.0, 23)
    expect(homogeneous.distanceTo(Vector4d(1.0, 2.0, 3.0, 4.0)) == 0.0, 24)
    expect(homogeneous.lerp(2.0 * homogeneous, 0.5) == 1.5 * homogeneous, 25)
    nearDouble(homogeneous.normalized().length, 1.0)
    expect(homogeneous.perspectiveDivide() == Vector3d(0.25, 0.5, 0.75), 26)
    expect(Vector4d(2.0, 4.0, 6.0, -2.0).perspectiveDivide() == Vector3d(-1.0, -2.0, -3.0), 27)
    expect(Vector4d(-2.0, 2.0, -2.0, 2.0).isInsideClipVolume, 28)
    expect(!Vector4d(2.01, 0.0, 0.0, 2.0).isInsideClipVolume, 29)
    for (invalid in listOf(0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
        rejected { Vector4d(1.0, 2.0, 3.0, invalid).perspectiveDivide() }
        expect(!Vector4d(0.0, 0.0, 0.0, invalid).isInsideClipVolume, 30)
    }
    expect(Vector2d(0.0, -0.0).normalized() == Vector2d(0.0, 0.0), 31)
    expect(Vector3d(-0.0, 0.0, 0.0).normalized() == Vector3d(0.0, 0.0, 0.0), 32)
    expect(Vector4d(0.0, 0.0, 0.0, -0.0).normalized() == Vector4d(0.0, 0.0, 0.0, 0.0), 33)
    for (size in listOf(Double.MAX_VALUE, Double.MIN_VALUE, 1.0e-300, 1.0e300)) {
        nearDouble(Vector2d(size, size).normalized().length, 1.0)
        nearDouble(Vector3d(size, size, size).normalized().length, 1.0)
        expect(Vector4d(size, size, size, size).normalized() == Vector4d(0.5, 0.5, 0.5, 0.5), 34)
        expect(Vector3d(size, 0.0, 0.0).normalized() == axis, 35)
    }
    val nonfinite = Vector3d(Double.POSITIVE_INFINITY, 1.0, 0.0).normalized()
    expect(nonfinite.x != nonfinite.x && nonfinite.y == 0.0 && nonfinite.z == 0.0, 36)
    val nan = Vector2d(Double.NaN, 1.0).normalized()
    expect(nan.x != nan.x && nan.y != nan.y, 37)
    val precise = Vector3d(16777217.0, 0.0, 0.0)
    expect(precise.x == 16777217.0 && precise.toFloat().x.toDouble() == 16777216.0, 38)
    expect(Vector2i(Int.MAX_VALUE, Int.MIN_VALUE).toDouble() == Vector2d(2147483647.0, -2147483648.0), 39)
    expect(Vector3i(1, 2, 3).toDouble() == Vector3d(1.0, 2.0, 3.0), 40)
    expect(Vector2f(3f, 4f).toDouble() == pair && pair.toFloat() == Vector2f(3f, 4f), 41)
    expect(Vector3f(1f, 0f, 0f).toDouble() == axis, 42)
    expect(Vector4f(1f, 2f, 3f, 4f).toDouble() == homogeneous && homogeneous.toFloat() == Vector4f(1f, 2f, 3f, 4f), 43)
    val quaternion = homogeneous.toQuaterniond()
    expect(quaternion == Quaterniond(1.0, 2.0, 3.0, 4.0), 44)
    expect(quaternion.toVector4d() == homogeneous, 45)
    val quaternionBox: Any = quaternion
    val vectorBox: Any = homogeneous
    expect(quaternionBox != vectorBox, 46)
    expect(quaternionBox == Quaterniond(1.0, 2.0, 3.0, 4.0), 47)
    val floatVector = Vector4f(1f, 2f, 3f, 4f)
    val floatQuaternion = floatVector.toQuaternionf()
    expect(floatQuaternion == Quaternionf(1f, 2f, 3f, 4f), 48)
    expect(floatQuaternion.toVector4f() == floatVector, 49)
    expect(floatQuaternion.toDouble() == quaternion && quaternion.toFloat() == floatQuaternion, 50)
    val floatQuaternionBox: Any = floatQuaternion
    val floatVectorBox: Any = floatVector
    expect(floatQuaternionBox != floatVectorBox && floatQuaternionBox != quaternionBox, 51)
    expect(Quaterniond(16777217.0, 0.0, 0.0, 1.0).toFloat().toDouble().x == 16777216.0, 52)
    val unusual = Vector4d(-0.0, Double.POSITIVE_INFINITY, 1.0e-300, 0.0).toQuaterniond().toVector4d()
    expect(1.0 / unusual.x == Double.NEGATIVE_INFINITY, 53)
    expect(unusual.y == Double.POSITIVE_INFINITY && unusual.z == 1.0e-300 && unusual.w == 0.0, 54)
    val stored = listOf<Any>(pair, axis, homogeneous)
    expect(stored[0] == Vector2d(3.0, 4.0) && stored[1] == axis && stored[2] == homogeneous, 55)
    expect(mutableSetOf(axis, Vector3d(1.0, 0.0, 0.0)).size == 1, 56)
    val normalize: () -> Vector3d = precise::normalized
    expect(normalize() == axis, 57)
}


fun verifyRotations() {
    val vector = Vector3d(1.0, 2.0, 3.0)
    val identity = Rotation3d.identity()
    expect(identity.transform(vector) == vector, 100)
    expect(identity.quaternion == Quaterniond(0.0, 0.0, 0.0, 1.0), 101)
    val turn = Rotation3d.fromQuaternion(Quaterniond(0.0, 0.0, 1.0, 1.0))
    nearDouble(turn.quaternion.toVector4d().length, 1.0)
    nearDouble(turn.transform(Vector3d(1.0, 0.0, 0.0)).x, 0.0)
    nearDouble(turn.transform(Vector3d(1.0, 0.0, 0.0)).y, 1.0)
    val undone = turn.inverse().transform(turn.transform(vector))
    nearDouble(undone.x, vector.x); nearDouble(undone.y, vector.y); nearDouble(undone.z, vector.z)
    val twice = (turn * turn).transform(Vector3d(1.0, 0.0, 0.0))
    nearDouble(twice.x, -1.0); nearDouble(twice.y, 0.0)
    for (size in listOf(Double.MAX_VALUE, Double.MIN_VALUE, 1.0e-300)) {
        val rotation = Rotation3d.fromQuaternion(Quaterniond(size, size, size, size))
        expect(rotation.quaternion == Quaterniond(0.5, 0.5, 0.5, 0.5), 102)
    }
    rejected { Rotation3d.fromQuaternion(Quaterniond(0.0, 0.0, 0.0, 0.0)) }
    for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
        rejected { Rotation3d.fromQuaternion(Quaterniond(bad, 0.0, 0.0, 1.0)) }
    }
    val floatIdentity = Rotation3f.identity()
    expect(floatIdentity.transform(Vector3f(1f, 2f, 3f)) == Vector3f(1f, 2f, 3f), 103)
    val floatTurn = Rotation3f.fromQuaternion(Quaternionf(0f, 0f, 1f, 1f))
    val rotated = floatTurn.transform(Vector3f(1f, 0f, 0f))
    expect(abs(rotated.x) < 2e-6f && abs(rotated.y - 1f) < 2e-6f, 104)
    val floatUndone = floatTurn.inverse().transform(rotated)
    expect(abs(floatUndone.x - 1f) < 2e-6f && abs(floatUndone.y) < 2e-6f, 105)
    val floatTwice = (floatTurn * floatTurn).transform(Vector3f(1f, 0f, 0f))
    expect(abs(floatTwice.x + 1f) < 2e-6f && abs(floatTwice.y) < 2e-6f, 106)
    for (size in listOf(Float.MAX_VALUE, Float.MIN_VALUE)) {
        expect(Rotation3f.fromQuaternion(Quaternionf(size, size, size, size)).quaternion == Quaternionf(0.5f, 0.5f, 0.5f, 0.5f), 107)
    }
    rejected { Rotation3f.fromQuaternion(Quaternionf(0f, 0f, 0f, 0f)) }
    for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
        rejected { Rotation3f.fromQuaternion(Quaternionf(0f, bad, 0f, 1f)) }
    }
    nearDouble(floatTurn.toDouble().quaternion.toVector4d().length, 1.0)
    expect(abs(turn.toFloat().quaternion.toVector4f().length - 1f) < 2e-6f, 108)
    val rotationBox: Any = identity
    val quaternionBox: Any = identity.quaternion
    expect(rotationBox != quaternionBox && rotationBox == Rotation3d.identity(), 109)
    val transform: (Vector3d) -> Vector3d = turn::transform
    nearDouble(transform(Vector3d(1.0, 0.0, 0.0)).y, 1.0)
    // Arbitrary quaternions stay unconstrained: Hamilton multiplication is ordinary scalar arithmetic.
    expect(Quaterniond(1.0, 0.0, 0.0, 0.0) * Quaterniond(0.0, 1.0, 0.0, 0.0) == Quaterniond(0.0, 0.0, 1.0, 0.0), 110)
}
