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

import kotlin.math.abs
import kotlin.math.hypot

/** SDK host counterpart of Guest Rotation3f; construction validates and normalizes components. */
@ConsistentCopyVisibility
data class Rotation3f private constructor(
    val quaternion: Quaternionf,
) {
    companion object {
        fun fromQuaternion(value: Quaternionf): Rotation3f {
            require(value.x.isFinite() && value.y.isFinite() && value.z.isFinite() && value.w.isFinite()) {
                "Rotation requires finite quaternion components"
            }
            val scale = maxOf(abs(value.x), abs(value.y), abs(value.z), abs(value.w))
            require(scale != 0.0f) { "Rotation requires a nonzero quaternion" }
            val x = value.x / scale
            val y = value.y / scale
            val z = value.z / scale
            val w = value.w / scale
            val magnitude = hypot(hypot(x, y), hypot(z, w))
            return Rotation3f(Quaternionf(x / magnitude, y / magnitude, z / magnitude, w / magnitude))
        }
    }
}

/** SDK host counterpart of Guest Rotation3d; construction validates and normalizes components. */
@ConsistentCopyVisibility
data class Rotation3d private constructor(
    val quaternion: Quaterniond,
) {
    companion object {
        fun fromQuaternion(value: Quaterniond): Rotation3d {
            require(value.x.isFinite() && value.y.isFinite() && value.z.isFinite() && value.w.isFinite()) {
                "Rotation requires finite quaternion components"
            }
            val scale = maxOf(abs(value.x), abs(value.y), abs(value.z), abs(value.w))
            require(scale != 0.0) { "Rotation requires a nonzero quaternion" }
            val x = value.x / scale
            val y = value.y / scale
            val z = value.z / scale
            val w = value.w / scale
            val magnitude = hypot(hypot(x, y), hypot(z, w))
            return Rotation3d(Quaterniond(x / magnitude, y / magnitude, z / magnitude, w / magnitude))
        }
    }
}
