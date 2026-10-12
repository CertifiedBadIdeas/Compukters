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

package ru.lazyhat.compukters.addon.api

import java.util.Collections

/** Constructor-safe raw geometry admitted from the canonical stdlib dependency. */
object CanonicalAddonRecordSchemas {
    val schemas: Map<String, AddonRecordSchema> =
        Collections.unmodifiableMap(
            listOf(
                "Vector2i",
                "Vector3i",
                "Vector2f",
                "Vector3f",
                "Vector4f",
                "Vector2d",
                "Vector3d",
                "Vector4d",
                "Quaternionf",
                "Quaterniond",
                "Matrix3f",
                "Matrix3d",
                "Matrix4f",
                "Matrix4d",
            ).associate { name ->
                val type =
                    when (name.last()) {
                        'i' -> AddonCapabilityValueType.I32
                        'f' -> AddonCapabilityValueType.F32
                        else -> AddonCapabilityValueType.F64
                    }
                val fields =
                    when {
                        name.startsWith("Matrix") -> {
                            val size = name[name.length - 2].digitToInt()
                            (0 until size).flatMap { row -> (0 until size).map { column -> "m$row$column" } }
                        }

                        name.startsWith("Quaternion") -> {
                            listOf("x", "y", "z", "w")
                        }

                        else -> {
                            listOf("x", "y", "z", "w").take(name[name.length - 2].digitToInt())
                        }
                    }
                val qualified = "kotlin.math.$name"
                qualified to AddonRecordSchema(qualified, fields.map { AddonRecordField(it, type) })
            },
        )
}
