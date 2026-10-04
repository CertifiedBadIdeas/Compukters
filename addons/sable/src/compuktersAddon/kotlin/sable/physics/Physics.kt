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

package sable.physics

public data class PhysicsVector(public val x: Double, public val y: Double, public val z: Double)

public data class PhysicsRotation(public val x: Double, public val y: Double, public val z: Double, public val w: Double)

/** An immutable observation copied at the request's server tick. */
public data class PhysicsSnapshot(
    public val constructionId: String,
    public val dimension: String,
    public val gameTick: Long,
    public val paused: Boolean,
    public val position: PhysicsVector,
    public val orientation: PhysicsRotation,
    public val scale: PhysicsVector,
    public val rotationPoint: PhysicsVector,
    public val linearVelocity: PhysicsVector,
    public val angularVelocity: PhysicsVector,
)

public object Physics {
    /** Observes the computer's current construction; throws IllegalStateException when unavailable. */
    public fun snapshot(): PhysicsSnapshot = PhysicsBindings.snapshot()
}

private object PhysicsBindings {
    external fun snapshot(): PhysicsSnapshot
}
