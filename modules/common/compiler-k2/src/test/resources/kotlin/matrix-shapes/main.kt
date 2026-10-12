/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import kotlin.math.*

fun main() {
    verifyMatrix3f()
    verifyMatrix3d()
    verifyMatrix4d()
    println("matrix shapes ok")
}

fun verifyMatrix3f() {
    val dense = Matrix3f(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
    val other = Matrix3f(9f, 8f, 7f, 6f, 5f, 4f, 3f, 2f, 1f)
    val identity = Matrix3f.identity()
    val product = dense * other
    for (row in 0..2) {
        for (column in 0..2) {
            var sum = dense[row, 0] * other[0, column]
            for (k in 1..2) sum += dense[row, k] * other[k, column]
            check(product[row, column] == sum) { "Matrix3f product $row $column" }
            check(dense.transposed()[row, column] == dense[column, row])
        }
    }
    check(dense * identity == dense && identity * dense == dense)
    check(dense.transposed().transposed() == dense)
    val vector = Vector3f(1f, 2f, 3f)
    val transformed = dense * vector
    for (row in 0..2) {
        var sum = dense[row, 0] * vector.x
        sum += dense[row, 1] * vector.y
        sum += dense[row, 2] * vector.z
        val component = when (row) { 0 -> transformed.x; 1 -> transformed.y; else -> transformed.z }
        check(component == sum)
    }
    var rejected = 0
    for (index in listOf(-1, 3, Int.MAX_VALUE)) {
        try { dense[index, 0] } catch (error: IndexOutOfBoundsException) { rejected++ }
        try { dense[0, index] } catch (error: IndexOutOfBoundsException) { rejected++ }
    }
    check(rejected == 6)
    check(Matrix3f.scale(2f) * vector == 2f * vector)
    check(Matrix3f.rotationX(0f) * vector == vector)
    check(Matrix3f.rotationY(0f) * vector == vector)
    check(Matrix3f.rotationZ(0f) * vector == vector)
    val boxed: Any = dense
    check(boxed == Matrix3f(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f))
    val transpose: () -> Matrix3f = dense::transposed
    check(transpose() == dense.transposed())
}

fun verifyMatrix3d() {
    val dense = Matrix3d(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0)
    val other = Matrix3d(9.0, 8.0, 7.0, 6.0, 5.0, 4.0, 3.0, 2.0, 1.0)
    val identity = Matrix3d.identity()
    val product = dense * other
    for (row in 0..2) {
        for (column in 0..2) {
            var sum = dense[row, 0] * other[0, column]
            for (k in 1..2) sum += dense[row, k] * other[k, column]
            check(product[row, column] == sum) { "Matrix3d product $row $column" }
            check(dense.transposed()[row, column] == dense[column, row])
        }
    }
    check(dense * identity == dense && identity * dense == dense)
    check(dense.transposed().transposed() == dense)
    val vector = Vector3d(1.0, 2.0, 3.0)
    val transformed = dense * vector
    for (row in 0..2) {
        var sum = dense[row, 0] * vector.x
        sum += dense[row, 1] * vector.y
        sum += dense[row, 2] * vector.z
        val component = when (row) { 0 -> transformed.x; 1 -> transformed.y; else -> transformed.z }
        check(component == sum)
    }
    var rejected = 0
    for (index in listOf(-1, 3, Int.MAX_VALUE)) {
        try { dense[index, 0] } catch (error: IndexOutOfBoundsException) { rejected++ }
        try { dense[0, index] } catch (error: IndexOutOfBoundsException) { rejected++ }
    }
    check(rejected == 6)
    check(Matrix3d.scale(2.0) * vector == 2.0 * vector)
    check(Matrix3d.rotationX(0.0) * vector == vector)
    check(Matrix3d.rotationY(0.0) * vector == vector)
    check(Matrix3d.rotationZ(0.0) * vector == vector)
    val boxed: Any = dense
    check(boxed == Matrix3d(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0))
    val transpose: () -> Matrix3d = dense::transposed
    check(transpose() == dense.transposed())
}

fun verifyMatrix4d() {
    val dense = Matrix4d(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0)
    val other = Matrix4d(16.0, 15.0, 14.0, 13.0, 12.0, 11.0, 10.0, 9.0, 8.0, 7.0, 6.0, 5.0, 4.0, 3.0, 2.0, 1.0)
    val identity = Matrix4d.identity()
    val product = dense * other
    for (row in 0..3) {
        for (column in 0..3) {
            var sum = dense[row, 0] * other[0, column]
            for (k in 1..3) sum += dense[row, k] * other[k, column]
            check(product[row, column] == sum) { "Matrix4d product $row $column" }
            check(dense.transposed()[row, column] == dense[column, row])
        }
    }
    check(dense * identity == dense && identity * dense == dense)
    check(dense.transposed().transposed() == dense)
    val vector = Vector4d(1.0, 2.0, 3.0, 4.0)
    val transformed = dense * vector
    for (row in 0..3) {
        var sum = dense[row, 0] * vector.x
        sum += dense[row, 1] * vector.y
        sum += dense[row, 2] * vector.z
        sum += dense[row, 3] * vector.w
        val component = when (row) { 0 -> transformed.x; 1 -> transformed.y; 2 -> transformed.z; else -> transformed.w }
        check(component == sum)
    }
    var rejected = 0
    for (index in listOf(-1, 4, Int.MAX_VALUE)) {
        try { dense[index, 0] } catch (error: IndexOutOfBoundsException) { rejected++ }
        try { dense[0, index] } catch (error: IndexOutOfBoundsException) { rejected++ }
    }
    check(rejected == 6)
    check(Matrix4d.scale(2.0) * vector == Vector4d(2.0, 4.0, 6.0, 4.0))
    check(Matrix4d.rotationX(0.0) * vector == vector)
    check(Matrix4d.rotationY(0.0) * vector == vector)
    check(Matrix4d.rotationZ(0.0) * vector == vector)
    val boxed: Any = dense
    check(boxed == Matrix4d(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0))
    val transpose: () -> Matrix4d = dense::transposed
    check(transpose() == dense.transposed())
    val point = Vector3d(1.0, 2.0, 3.0)
    val translation = Matrix4d.translation(Vector3d(10.0, 20.0, 30.0))
    check(translation * point == Vector4d(11.0, 22.0, 33.0, 1.0))
    check(translation.transformPoint(point) == translation * point)
    check(translation.transformDirection(point) == Vector4d(1.0, 2.0, 3.0, 0.0))
    val projection = Matrix4d.perspective(PI / 2.0, 2.0, 1.0, 9.0)
    val near = (projection * Vector3d(0.0, 0.0, -1.0)).perspectiveDivide()
    val far = (projection * Vector3d(0.0, 0.0, -9.0)).perspectiveDivide()
    check(abs(near.z + 1.0) < 1e-14 && abs(far.z - 1.0) < 1e-14)
    val ortho = Matrix4d.orthographic(-2.0, 6.0, -1.0, 3.0, 1.0, 9.0)
    check((ortho * Vector3d(2.0, 1.0, -5.0)).perspectiveDivide() == Vector3d(0.0, 0.0, 0.0))
    val wide = Matrix4d.orthographic(-Double.MAX_VALUE, Double.MAX_VALUE, -1.0, 1.0, -Double.MAX_VALUE, Double.MAX_VALUE)
    check(wide.m00 > 0.0 && wide.m22 < 0.0 && wide.m23 == 0.0)
    val huge = Matrix4d.perspective(PI / 2.0, 1.0, Double.MAX_VALUE / 4.0, Double.MAX_VALUE / 2.0)
    check(abs(huge.m22) <= Double.MAX_VALUE && abs(huge.m23) <= Double.MAX_VALUE)
    var rejectedProjection = 0
    for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
        try { Matrix4d.perspective(PI / 2.0, bad, 1.0, 9.0) } catch (error: IllegalArgumentException) { rejectedProjection++ }
        try { Matrix4d.perspective(bad, 1.0, 1.0, 9.0) } catch (error: IllegalArgumentException) { rejectedProjection++ }
        try { Matrix4d.perspective(PI / 2.0, 1.0, bad, 9.0) } catch (error: IllegalArgumentException) { rejectedProjection++ }
    }
    check(rejectedProjection == 12)
    val precise = Vector4d(16777217.0, 1.0e-100, 0.0, 1.0)
    check(identity * precise == precise)
}
