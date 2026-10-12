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

package ru.lazyhat.compukters.compiler.artifact.model

/** Bounded row-major kernels. Original Matrix4f selectors require 1.17; additional shapes require 1.19. */
enum class MatrixOperation(
    val selector: UInt,
    val rightComponents: Int?,
    val resultComponents: Int,
    val fixedCost: UInt,
    val leftComponents: Int = 16,
    val scalarType: ScalarValueType = ScalarValueType.F32,
    val runtimeMinor: UShort = 17u,
) {
    TRANSPOSE(1u, null, 16, 18u),
    MULTIPLY_MATRIX(2u, 16, 16, 128u),
    MULTIPLY_VECTOR(3u, 4, 4, 32u),
    TRANSFORM_POINT(4u, 3, 4, 32u),
    TRANSFORM_DIRECTION(5u, 3, 4, 32u),
    TRANSPOSE_3F(6u, null, 9, 11u, 9, ScalarValueType.F32, 19u),
    MULTIPLY_MATRIX_3F(7u, 9, 9, 54u, 9, ScalarValueType.F32, 19u),
    MULTIPLY_VECTOR_3F(8u, 3, 3, 18u, 9, ScalarValueType.F32, 19u),
    TRANSPOSE_3D(9u, null, 9, 11u, 9, ScalarValueType.F64, 19u),
    MULTIPLY_MATRIX_3D(10u, 9, 9, 54u, 9, ScalarValueType.F64, 19u),
    MULTIPLY_VECTOR_3D(11u, 3, 3, 18u, 9, ScalarValueType.F64, 19u),
    TRANSPOSE_4D(12u, null, 16, 18u, 16, ScalarValueType.F64, 19u),
    MULTIPLY_MATRIX_4D(13u, 16, 16, 128u, 16, ScalarValueType.F64, 19u),
    MULTIPLY_VECTOR_4D(14u, 4, 4, 32u, 16, ScalarValueType.F64, 19u),
    TRANSFORM_POINT_4D(15u, 3, 4, 32u, 16, ScalarValueType.F64, 19u),
    TRANSFORM_DIRECTION_4D(16u, 3, 4, 32u, 16, ScalarValueType.F64, 19u),
}
