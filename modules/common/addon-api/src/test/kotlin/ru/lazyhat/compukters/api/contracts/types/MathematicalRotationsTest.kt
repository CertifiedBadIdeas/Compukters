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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MathematicalRotationsTest {
    @Test
    fun doubleRotationsRejectInvalidValuesAndNormalizeExtremeMagnitudes() {
        for (invalid in listOf(0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { Rotation3d.fromQuaternion(Quaterniond(0.0, 0.0, invalid, 0.0)) }
        }
        for (scale in listOf(2.0, Double.MAX_VALUE, Double.MIN_VALUE)) {
            val q = Rotation3d.fromQuaternion(Quaterniond(scale, 0.0, 0.0, -scale)).quaternion
            assertEquals(1.0, q.x * q.x + q.w * q.w, 1e-15)
            assertEquals(-q.x, q.w)
            assertEquals(0.0, q.y)
            assertEquals(0.0, q.z)
        }
    }

    @Test
    fun floatRotationsRejectInvalidValuesAndNormalizeExtremeMagnitudes() {
        for (invalid in listOf(0.0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { Rotation3f.fromQuaternion(Quaternionf(0.0f, invalid, 0.0f, 0.0f)) }
        }
        for (scale in listOf(2.0f, Float.MAX_VALUE, Float.MIN_VALUE)) {
            val q = Rotation3f.fromQuaternion(Quaternionf(0.0f, scale, 0.0f, -scale)).quaternion
            assertEquals(1.0f, q.y * q.y + q.w * q.w, 1e-6f)
            assertEquals(-q.y, q.w)
            assertEquals(0.0f, q.x)
            assertEquals(0.0f, q.z)
        }
    }
}
