/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.api.contracts.types

/** JVM values for addon contracts and generated hosts. Guest libraries use the canonical stdlib types. */
data class Vector2i(
    val x: Int,
    val y: Int,
)

data class Vector3i(
    val x: Int,
    val y: Int,
    val z: Int,
)

data class Vector2f(
    val x: Float,
    val y: Float,
)

data class Vector3f(
    val x: Float,
    val y: Float,
    val z: Float,
)

data class Vector4f(
    val x: Float,
    val y: Float,
    val z: Float,
    val w: Float,
)

data class Vector2d(
    val x: Double,
    val y: Double,
)

data class Vector3d(
    val x: Double,
    val y: Double,
    val z: Double,
)

data class Vector4d(
    val x: Double,
    val y: Double,
    val z: Double,
    val w: Double,
)

data class Quaternionf(
    val x: Float,
    val y: Float,
    val z: Float,
    val w: Float,
)

data class Quaterniond(
    val x: Double,
    val y: Double,
    val z: Double,
    val w: Double,
)

data class Matrix3f(
    val m00: Float,
    val m01: Float,
    val m02: Float,
    val m10: Float,
    val m11: Float,
    val m12: Float,
    val m20: Float,
    val m21: Float,
    val m22: Float,
)

data class Matrix3d(
    val m00: Double,
    val m01: Double,
    val m02: Double,
    val m10: Double,
    val m11: Double,
    val m12: Double,
    val m20: Double,
    val m21: Double,
    val m22: Double,
)

data class Matrix4f(
    val m00: Float,
    val m01: Float,
    val m02: Float,
    val m03: Float,
    val m10: Float,
    val m11: Float,
    val m12: Float,
    val m13: Float,
    val m20: Float,
    val m21: Float,
    val m22: Float,
    val m23: Float,
    val m30: Float,
    val m31: Float,
    val m32: Float,
    val m33: Float,
)

data class Matrix4d(
    val m00: Double,
    val m01: Double,
    val m02: Double,
    val m03: Double,
    val m10: Double,
    val m11: Double,
    val m12: Double,
    val m13: Double,
    val m20: Double,
    val m21: Double,
    val m22: Double,
    val m23: Double,
    val m30: Double,
    val m31: Double,
    val m32: Double,
    val m33: Double,
)
