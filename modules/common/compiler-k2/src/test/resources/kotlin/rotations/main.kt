/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import kotlin.math.*
import kotlin.random.Random

fun nearRotation(actual: Double, expected: Double, tolerance: Double = 3e-14) {
    check(abs(actual - expected) <= tolerance) { "Expected $expected, got $actual" }
}

fun vectorNear(actual: Vector3d, expected: Vector3d, tolerance: Double = 3e-14) {
    nearRotation(actual.x, expected.x, tolerance)
    nearRotation(actual.y, expected.y, tolerance)
    nearRotation(actual.z, expected.z, tolerance)
}

fun rejectsRotation(block: () -> Unit) {
    var rejected = false
    try { block() } catch (error: IllegalArgumentException) { rejected = true }
    check(rejected) { "Rotation input must be rejected" }
}

fun main() {
    try {
        verifyAxisRotations()
        verifyDirectionRotations()
        verifyInterpolation()
        verifyRotationChains()
        println("rotations ok")
    } catch (error: Throwable) {
        println(error.message ?: "rotation failure")
        throw error
    }
}

fun verifyAxisRotations() {
    val x = Vector3d(1.0, 0.0, 0.0)
    val y = Vector3d(0.0, 1.0, 0.0)
    val z = Vector3d(0.0, 0.0, 1.0)
    val quarter = PI / 2.0
    vectorNear(Rotation3d.rotationX(quarter).transform(y), z)
    vectorNear(Rotation3d.rotationY(quarter).transform(z), x)
    vectorNear(Rotation3d.rotationZ(quarter).transform(x), y)
    vectorNear(Rotation3d.rotationZ(-quarter).transform(x), -y)
    for (size in listOf(Double.MIN_VALUE, 1e-300, 1.0, Double.MAX_VALUE)) {
        val scaledAxis = Rotation3d.fromAxisAngle(Vector3d(0.0, size, 0.0), quarter)
        vectorNear(scaledAxis.transform(z), x)
        nearRotation(scaledAxis.quaternion.toVector4d().length, 1.0)
    }
    val vector = Vector3d(2.0, -3.0, 7.0)
    val rotation = Rotation3d.fromAxisAngle(Vector3d(1.0, 2.0, 3.0), 1.23)
    vectorNear(rotation.toMatrix3d() * vector, rotation.transform(vector))
    vectorNear((rotation.toMatrix4d() * vector).xyz, rotation.transform(vector))
    nearRotation((rotation.toMatrix4d() * vector).w, 1.0)
    val xy = Rotation3d.rotationX(quarter) * Rotation3d.rotationY(quarter)
    val yx = Rotation3d.rotationY(quarter) * Rotation3d.rotationX(quarter)
    vectorNear(xy.transform(z), x)
    vectorNear(yx.transform(z), -y)
    vectorNear(xy.transform(vector), Rotation3d.rotationX(quarter).transform(Rotation3d.rotationY(quarter).transform(vector)))
    vectorNear(xy.toMatrix3d() * vector, (Rotation3d.rotationX(quarter).toMatrix3d() * Rotation3d.rotationY(quarter).toMatrix3d()) * vector)
    rejectsRotation { Rotation3d.fromAxisAngle(Vector3d(0.0, 0.0, 0.0), 0.0) }
    for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
        rejectsRotation { Rotation3d.fromAxisAngle(Vector3d(bad, 0.0, 1.0), quarter) }
        rejectsRotation { Rotation3d.fromAxisAngle(x, bad) }
    }
    val xFloat = x.toFloat()
    val yFloat = y.toFloat()
    val zFloat = z.toFloat()
    vectorNear(Rotation3f.rotationX(quarter.toFloat()).transform(yFloat).toDouble(), z, 3e-7)
    vectorNear(Rotation3f.rotationY(quarter.toFloat()).transform(zFloat).toDouble(), x, 3e-7)
    vectorNear(Rotation3f.rotationZ(quarter.toFloat()).transform(xFloat).toDouble(), y, 3e-7)
    for (size in listOf(Float.MIN_VALUE, Float.MAX_VALUE)) {
        vectorNear(Rotation3f.fromAxisAngle(Vector3f(0f, size, 0f), quarter.toFloat()).transform(zFloat).toDouble(), x, 3e-7)
    }
    val floatRotation = Rotation3f.fromAxisAngle(Vector3f(1f, 2f, 3f), 1.23f)
    val floatVector = vector.toFloat()
    vectorNear((floatRotation.toMatrix3f() * floatVector).toDouble(), floatRotation.transform(floatVector).toDouble(), 3e-6)
    vectorNear((floatRotation.toMatrix4f() * floatVector).xyz.toDouble(), floatRotation.transform(floatVector).toDouble(), 3e-6)
    rejectsRotation { Rotation3f.fromAxisAngle(Vector3f(0f, 0f, 0f), 0f) }
    for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
        rejectsRotation { Rotation3f.fromAxisAngle(Vector3f(0f, bad, 1f), 0f) }
        rejectsRotation { Rotation3f.rotationX(bad) }
    }
    val fromAxis: (Vector3d, Double) -> Rotation3d = Rotation3d::fromAxisAngle
    vectorNear(fromAxis(y, quarter).transform(z), x)
    val matrices: () -> Matrix3d = rotation::toMatrix3d
    vectorNear(matrices() * vector, rotation.transform(vector))
}

fun verifyDirectionRotations() {
    val x = Vector3d(1.0, 0.0, 0.0)
    val y = Vector3d(0.0, 1.0, 0.0)
    val z = Vector3d(0.0, 0.0, 1.0)
    val identity = Rotation3d.identity()
    vectorNear(Rotation3d.fromTo(x, y).transform(x), y)
    vectorNear(Rotation3d.fromTo(y, z).transform(y), z)
    vectorNear(Rotation3d.fromTo(z, x).transform(z), x)
    check(Rotation3d.fromTo(x, x * 17.0) == identity)
    for (source in listOf(x, y, z, Vector3d(1.0, 1.0, 1.0), Vector3d(2.0, -3.0, 7.0))) {
        val opposite = Rotation3d.fromTo(source, -source)
        vectorNear(opposite.transform(source.normalized()), -source.normalized())
        nearRotation(identity.angleTo(opposite), PI)
        check(opposite == Rotation3d.fromTo(source, -source))
    }
    // For +X the least aligned axis is +Y, so the deterministic half-turn axis is +Z.
    vectorNear(Rotation3d.fromTo(x, -x).quaternion.toVector4d().xyz, z)
    for (size in listOf(Double.MIN_VALUE, 1e-300, 1.0, Double.MAX_VALUE)) {
        vectorNear(Rotation3d.fromTo(Vector3d(size, 0.0, 0.0), Vector3d(0.0, -size, 0.0)).transform(x), -y)
        vectorNear(Rotation3d.fromTo(Vector3d(size, size, size), Vector3d(-size, size, -size)).transform(Vector3d(1.0, 1.0, 1.0).normalized()), Vector3d(-1.0, 1.0, -1.0).normalized())
    }
    // Tiny departures must not be collapsed by an epsilon or acos(dot).
    val tinyTarget = Vector3d(1.0, 1e-300, 0.0)
    val tinyRotation = Rotation3d.fromTo(x, tinyTarget)
    val tinyResult = tinyRotation.transform(x)
    // Relative checks ensure that zero cannot pass an absolute tolerance much larger than the angle.
    nearRotation(tinyResult.y / tinyTarget.y, 1.0, 2e-15)
    nearRotation(identity.angleTo(tinyRotation) / 1e-300, 1.0, 2e-15)
    nearRotation(tinyRotation.quaternion.z / 5e-301, 1.0, 2e-15)
    check(tinyResult.x == 1.0 && tinyResult.z == 0.0)
    check(tinyRotation != identity)
    for (sign in listOf(-1.0, 1.0)) {
        val parallelTarget = Vector3d(1.0, sign * 1e-12, 0.0).normalized()
        val nearParallel = Rotation3d.fromTo(x, parallelTarget)
        vectorNear(nearParallel.transform(x), parallelTarget, 1e-26)
        nearRotation(identity.angleTo(nearParallel), 1e-12, 1e-26)
        val oppositeTarget = Vector3d(-1.0, sign * 1e-12, 0.0).normalized()
        val nearOpposite = Rotation3d.fromTo(x, oppositeTarget)
        vectorNear(nearOpposite.transform(x), oppositeTarget)
        check(nearOpposite.quaternion.z * sign > 0.0)
    }
    val random = Random(754)
    for (i in 0..63) {
        val from = Vector3d(random.nextDouble() - 0.5, random.nextDouble() - 0.5, random.nextDouble() - 0.5)
        val to = Vector3d(random.nextDouble() - 0.5, random.nextDouble() - 0.5, random.nextDouble() - 0.5)
        val a = from.normalized()
        val b = to.normalized()
        val rotation = Rotation3d.fromTo(from, to)
        vectorNear(rotation.transform(a), b)
        nearRotation(identity.angleTo(rotation), atan2((a cross b).length, a dot b))
        nearRotation(rotation.transform(from).length, from.length)
        vectorNear(rotation.inverse().transform(b), a)
        val floatRotation = Rotation3f.fromTo(from.toFloat(), to.toFloat())
        vectorNear(floatRotation.transform(from.toFloat().normalized()).toDouble(), to.toFloat().normalized().toDouble(), 5e-7)
        nearRotation(Rotation3f.identity().angleTo(floatRotation).toDouble(), identity.angleTo(rotation), 5e-7)
    }
    val zero = Vector3d(0.0, 0.0, 0.0)
    rejectsRotation { Rotation3d.fromTo(zero, x) }
    rejectsRotation { Rotation3d.fromTo(x, zero) }
    for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
        for (invalid in listOf(Vector3d(bad, 1.0, 0.0), Vector3d(1.0, bad, 0.0), Vector3d(1.0, 0.0, bad))) {
            rejectsRotation { Rotation3d.fromTo(invalid, x) }
            rejectsRotation { Rotation3d.fromTo(x, invalid) }
        }
    }
    val factory: (Vector3d, Vector3d) -> Rotation3d = Rotation3d::fromTo
    vectorNear(factory(x, y).transform(x), y)
    val floatX = x.toFloat()
    val floatY = y.toFloat()
    val floatIdentity = Rotation3f.identity()
    vectorNear(Rotation3f.fromTo(floatX, floatY).transform(floatX).toDouble(), y, 3e-7)
    vectorNear(Rotation3f.fromTo(floatX, -floatX).transform(floatX).toDouble(), -x, 3e-7)
    check(Rotation3f.fromTo(floatX, floatX * 17f) == floatIdentity)
    for (size in listOf(Float.MIN_VALUE, Float.MAX_VALUE)) {
        vectorNear(Rotation3f.fromTo(Vector3f(size, 0f, 0f), Vector3f(0f, -size, 0f)).transform(floatX).toDouble(), -y, 3e-7)
    }
    for (component in listOf(1e-30f, Float.MIN_VALUE * 4f)) {
        val target = Vector3f(1f, component, 0f)
        val rotation = Rotation3f.fromTo(floatX, target)
        val result = rotation.transform(floatX)
        nearRotation((result.y / component).toDouble(), 1.0, 2e-7)
        nearRotation((floatIdentity.angleTo(rotation) / component).toDouble(), 1.0, 2e-7)
        check(result.x == 1f && result.z == 0f && rotation != floatIdentity)
    }
    for (sign in listOf(-1f, 1f)) {
        val target = Vector3f(-1f, sign * 1e-5f, 0f).normalized()
        val rotation = Rotation3f.fromTo(floatX, target)
        vectorNear(rotation.transform(floatX).toDouble(), target.toDouble(), 3e-7)
        check(rotation.quaternion.z * sign > 0f)
    }
    val floatZero = Vector3f(0f, 0f, 0f)
    rejectsRotation { Rotation3f.fromTo(floatZero, floatX) }
    rejectsRotation { Rotation3f.fromTo(floatX, floatZero) }
    for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
        for (invalid in listOf(Vector3f(bad, 1f, 0f), Vector3f(1f, bad, 0f), Vector3f(1f, 0f, bad))) {
            rejectsRotation { Rotation3f.fromTo(invalid, floatX) }
            rejectsRotation { Rotation3f.fromTo(floatX, invalid) }
        }
    }
    val floatFactory: (Vector3f, Vector3f) -> Rotation3f = Rotation3f::fromTo
    vectorNear(floatFactory(floatX, floatY).transform(floatX).toDouble(), y, 3e-7)
}

fun verifyInterpolation() {
    val identity = Rotation3d.identity()
    val target = Rotation3d.rotationZ(PI * 0.75)
    val x = Vector3d(1.0, 0.0, 0.0)
    for (i in 0..8) {
        val amount = i.toDouble() / 8.0
        val interpolated = identity.slerp(target, amount)
        nearRotation(identity.angleTo(interpolated), amount * PI * 0.75)
        nearRotation(interpolated.angleTo(target), (1.0 - amount) * PI * 0.75)
        nearRotation(interpolated.quaternion.toVector4d().length, 1.0)
    }
    check(identity.slerp(target, 0.0) == identity && identity.slerp(target, 1.0) == target)
    val q = target.quaternion
    val opposite = Rotation3d.fromQuaternion(Quaterniond(-q.x, -q.y, -q.z, -q.w))
    vectorNear(target.transform(x), opposite.transform(x))
    nearRotation(target.angleTo(opposite), 0.0)
    vectorNear(target.slerp(opposite, 0.37).transform(x), target.transform(x))
    vectorNear(identity.slerp(opposite, 0.37).transform(x), identity.slerp(target, 0.37).transform(x))
    // This angle is smaller than acos(dot) can resolve around dot = 1.
    val tiny = Rotation3d.rotationY(1e-12)
    nearRotation(identity.angleTo(tiny), 1e-12, 1e-26)
    nearRotation(identity.angleTo(identity.slerp(tiny, 0.25)), 2.5e-13, 1e-26)
    val half = identity.slerp(Rotation3d.rotationZ(PI), 0.5)
    vectorNear(half.transform(x), Vector3d(0.0, 1.0, 0.0))
    val acrossSign = identity.slerp(Rotation3d.rotationZ(PI * 1.5), 0.5)
    vectorNear(acrossSign.transform(x), Vector3d(sqrt(0.5), -sqrt(0.5), 0.0))
    vectorNear(identity.slerp(Rotation3d.rotationZ(PI / 2.0), 2.0).transform(x), -x)
    vectorNear(identity.slerp(Rotation3d.rotationZ(PI / 2.0), -1.0).transform(x), Vector3d(0.0, -1.0, 0.0))
    rejectsRotation { identity.slerp(Rotation3d.rotationZ(PI), Double.MAX_VALUE) }
    val start = Rotation3d.fromAxisAngle(Vector3d(1.0, 3.0, -2.0), 0.73)
    val finish = start * target
    vectorNear(start.slerp(finish, 0.25).transform(x), (start * Rotation3d.rotationZ(PI * 0.75 * 0.25)).transform(x))
    for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
        rejectsRotation { identity.slerp(target, bad) }
    }
    val floatIdentity = Rotation3f.identity()
    val floatTarget = target.toFloat()
    for (i in 0..8) {
        val amount = i.toFloat() / 8f
        val interpolated = floatIdentity.slerp(floatTarget, amount)
        nearRotation(floatIdentity.angleTo(interpolated).toDouble(), amount.toDouble() * PI * 0.75, 3e-7)
    }
    check(floatIdentity.slerp(floatTarget, 0f) == floatIdentity && floatIdentity.slerp(floatTarget, 1f) == floatTarget)
    vectorNear(floatIdentity.slerp(Rotation3f.rotationZ((PI / 2.0).toFloat()), 2f).transform(x.toFloat()).toDouble(), -x, 3e-7)
    vectorNear(floatIdentity.slerp(Rotation3f.rotationZ((PI / 2.0).toFloat()), -1f).transform(x.toFloat()).toDouble(), Vector3d(0.0, -1.0, 0.0), 3e-7)
    val floatOpposite = opposite.toFloat()
    vectorNear(floatTarget.slerp(floatOpposite, 0.5f).transform(x.toFloat()).toDouble(), floatTarget.transform(x.toFloat()).toDouble(), 3e-7)
    nearRotation(floatTarget.angleTo(floatOpposite).toDouble(), 0.0, 3e-7)
    for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
        rejectsRotation { floatIdentity.slerp(floatTarget, bad) }
    }
    val interpolate: (Rotation3d, Double) -> Rotation3d = start::slerp
    vectorNear(interpolate(finish, 0.25).transform(x), start.slerp(finish, 0.25).transform(x))
}

fun verifyRotationChains() {
    val random = Random(753)
    var doubleRotation = Rotation3d.identity()
    var floatRotation = Rotation3f.identity()
    var floatReference = Rotation3d.identity()
    for (i in 0..2047) {
        val axis = Vector3d(random.nextDouble() - 0.5, random.nextDouble() - 0.5, random.nextDouble() - 0.5)
        val angle = (random.nextDouble() - 0.5) * 0.1
        val step = Rotation3d.fromAxisAngle(axis, angle)
        val floatStep = Rotation3f.fromAxisAngle(axis.toFloat(), angle.toFloat())
        doubleRotation = doubleRotation * step
        floatRotation = floatRotation * floatStep
        floatReference = floatReference * floatStep.toDouble()
        val vector = Vector3d(random.nextDouble() * 10.0, random.nextDouble() * 10.0, random.nextDouble() * 10.0)
        val transformed = doubleRotation.transform(vector)
        nearRotation(transformed.length, vector.length, 5e-14)
        nearRotation(doubleRotation.quaternion.toVector4d().length, 1.0, 5e-16)
        val floatVector = vector.toFloat()
        val floatLength = floatVector.length.toDouble()
        val floatResult = floatRotation.transform(floatVector)
        nearRotation(floatResult.length.toDouble(), floatLength, floatLength * 5e-7 + 1e-7)
        nearRotation(floatRotation.quaternion.toVector4f().length.toDouble(), 1.0, 2e-7)
        if (i % 64 == 0) {
            val q = doubleRotation.quaternion
            val opposite = Rotation3d.fromQuaternion(Quaterniond(-q.x, -q.y, -q.z, -q.w))
            vectorNear(opposite.transform(vector), transformed)
            vectorNear(doubleRotation.toMatrix3d() * vector, transformed, 5e-14)
        }
    }
    nearRotation(floatRotation.toDouble().angleTo(floatReference), 0.0, 2e-5)
}
