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
import kotlin.math.sqrt

fun main() {
    val status = Space.status()
    println("Dimension: " + status.dimension)
    println("Deep Space: " + status.inDeepSpace)
    try {
        val planet = Space.planet()
        println("Planet frame: " + planet.frame)
        println("Transition altitude (m): " + planet.transitionHeight)
        val mapping = Space.planetMapping()
        println("Surface dimension: " + mapping.dimension)
        println("Planet shape: " + if (mapping.spherical) "sphere" else "cube")
        println("Border: " + mapping.borderCenterX + ", " + mapping.borderCenterZ + "; size " + mapping.borderSize)
        println("Planet influence radius (m): " + mapping.influenceRadius)
        println("Mapping orbit tick: " + mapping.universeTick)
        println("Rotation model epoch (s): " + mapping.rotation.epochSeconds)
        println("Rotation rate (rad/s): " + mapping.rotation.rate.length)
        if (status.inDeepSpace) {
            val orbit = Space.orbit()
            println("Orbit frame: " + orbit.frame)
            println("Instance: " + orbit.instanceId + "; orbit tick: " + orbit.universeTick)
            val radius = orbit.position.length
            val radialOut = orbit.position.normalized()
            val radialSpeed = orbit.velocity.x * radialOut.x + orbit.velocity.y * radialOut.y + orbit.velocity.z * radialOut.z
            val transverseSpeed = (orbit.velocity - radialOut * radialSpeed).length
            println("Orbital radius (m): " + radius)
            println("Orbital speed (m/s): " + orbit.velocity.length)
            println("Radial speed (m/s): " + radialSpeed)
            println("Transverse speed (m/s): " + transverseSpeed)
            println("Circular speed at radius (m/s): " + sqrt(orbit.gravitationalParameter / radius))
            println("Bound: " + orbit.bound)
            if (orbit.frame == planet.frame) {
                println("Radius above planet radius (m): " + (radius - planet.radius))
                println("Periapsis above planet radius (m): " + (orbit.periapsisRadius() - planet.radius))
                if (orbit.bound) println("Apoapsis above planet radius (m): " + (orbit.apoapsisRadius() - planet.radius))
            } else {
                println("Planet frame changed between observations; planet-relative distances omitted.")
            }
            val region = Space.region()
            if (region.instanceId == orbit.instanceId && region.frame == orbit.frame) {
                println("Region side (m): " + region.sideLength + "; timescale " + region.timeScale)
                println("Region offset (m): " + (region.constructionPosition - region.center).length)
                println("Construction diagonal (m): " + region.constructionDiagonal)
                println("Region constructions: " + region.loadedConstructions + " loaded; " + region.unloadedConstructions + " tracked unloaded")
            } else {
                println("Region changed between observations; region comparison omitted.")
            }
            println("Bound does not prove planet clearance. Distances use the planet radius, not its shape.")
        }
    } catch (failure: IllegalStateException) {
        println("Space observation unavailable: " + failure.message)
    }
}
