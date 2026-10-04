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

package ru.lazyhat.compukters.integration.sable

import java.util.UUID

internal data class PhysicsVector(
    val x: Double,
    val y: Double,
    val z: Double,
)

internal data class PhysicsRotation(
    val x: Double,
    val y: Double,
    val z: Double,
    val w: Double,
)

/** Immutable host copy made on demand; gameTick is a world timestamp, not a physics step. */
internal data class PhysicsSnapshot(
    val constructionId: UUID,
    val gameTick: Long,
    val position: PhysicsVector,
    val orientation: PhysicsRotation,
    val scale: PhysicsVector,
    val rotationPoint: PhysicsVector,
    val linearVelocity: PhysicsVector,
    val angularVelocity: PhysicsVector,
)
