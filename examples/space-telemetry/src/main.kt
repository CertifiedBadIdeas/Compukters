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

import cosmonautics.space.Space

fun main() {
    val status = Space.status()
    println("Dimension: " + status.dimension)
    println("Deep Space: " + status.inDeepSpace)
    try {
        val planet = Space.planet()
        println("Planet frame: " + planet.frame)
        println("Transition altitude (m): " + planet.transitionHeight)
        if (status.inDeepSpace) {
            val orbit = Space.orbit()
            println("Orbit frame: " + orbit.frame)
            println("Bound: " + orbit.bound)
            if (orbit.frame == planet.frame) {
                println("Periapsis altitude (m): " + (orbit.periapsisRadius() - planet.radius))
                if (orbit.bound) println("Apoapsis altitude (m): " + (orbit.apoapsisRadius() - planet.radius))
            }
        }
    } catch (failure: IllegalStateException) {
        println("Space observation unavailable: " + failure.message)
    }
}
