/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */

private class Particle(
    var position: Int,
    var velocity: Int,
    val weight: Int,
)

fun main() {
    println("ready")
    val particles = ArrayList<Particle>(64)
    val totals = IntArray(64)
    var index = 0
    while (index < totals.size) {
        particles.add(Particle(index, index % 7 + 1, index % 5 + 1))
        index += 1
    }
    var checksum = 0
    var step = 0
    while (step < 1024) {
        index = 0
        while (index < totals.size) {
            val particle = particles[index]
            particle.position += particle.velocity
            particle.velocity = (particle.velocity + 1) and 7
            totals[index] += particle.position
            checksum += totals[index] * particle.weight
            index += 1
        }
        step += 1
    }
    println(checksum)
}
