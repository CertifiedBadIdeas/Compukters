/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.worker.k2

internal data class ExecutionBenchmarkCase(
    val id: String,
    val source: String,
    val checksum: Long,
)

internal fun executionBenchmarkCases(): List<ExecutionBenchmarkCase> {
    val sums =
        listOf(4096, 200000).map { count ->
            ExecutionBenchmarkCase(
                id = "sum-f64-$count",
                source =
                    """
                    fun main() {
                        println("ready")
                        var index = 0
                        var x = 0.0
                        var sum = 0.0
                        while (index < $count) {
                            x += 0.5
                            sum += x * 2.0
                            index += 1
                        }
                        println(sum.toLong())
                    }
                    """.trimIndent(),
                checksum = count.toLong() * (count + 1) / 2,
            )
        }
    val steps = 20000

    fun axis(target: Double): Pair<Double, Double> {
        var position = 0.0
        var velocity = 0.0
        var integral = 0.0
        var previous = 0.0
        repeat(steps) {
            val error = target - position
            integral += error * 0.125
            val derivative = (error - previous) * 8.0
            val force = error * 0.5 + integral * 0.01 + derivative * 0.125
            velocity = velocity * 0.875 + force * 0.125
            position += velocity * 0.125
            previous = error
        }
        return position to velocity
    }
    val scalar = axis(10.0)
    val x = scalar
    val y = axis(20.0)
    val z = axis(-5.0)
    return sums +
        listOf(
            ExecutionBenchmarkCase(
                "pid-f64-$steps",
                """
                fun main() {
                    println("ready")
                    var position = 0.0
                    var velocity = 0.0
                    var integral = 0.0
                    var previous = 0.0
                    var index = 0
                    while (index < $steps) {
                        val error = 10.0 - position
                        integral += error * 0.125
                        val derivative = (error - previous) * 8.0
                        val force = error * 0.5 + integral * 0.01 + derivative * 0.125
                        velocity = velocity * 0.875 + force * 0.125
                        position += velocity * 0.125
                        previous = error
                        index += 1
                    }
                    println(((position + velocity) * 1000000.0).toLong())
                }
                """.trimIndent(),
                ((scalar.first + scalar.second) * 1000000.0).toLong(),
            ),
            ExecutionBenchmarkCase(
                "pid-vec3-f64-$steps",
                """
                value class Vec3(val x: Double, val y: Double, val z: Double) {
                    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
                    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
                    operator fun times(scale: Double) = Vec3(x * scale, y * scale, z * scale)
                }
                fun main() {
                    println("ready")
                    val target = Vec3(10.0, 20.0, -5.0)
                    var position = Vec3(0.0, 0.0, 0.0)
                    var velocity = Vec3(0.0, 0.0, 0.0)
                    var integral = Vec3(0.0, 0.0, 0.0)
                    var previous = Vec3(0.0, 0.0, 0.0)
                    var index = 0
                    while (index < $steps) {
                        val error = target - position
                        integral += error * 0.125
                        val derivative = (error - previous) * 8.0
                        val force = error * 0.5 + integral * 0.01 + derivative * 0.125
                        velocity = velocity * 0.875 + force * 0.125
                        position += velocity * 0.125
                        previous = error
                        index += 1
                    }
                    println(((position.x + position.y + position.z + velocity.x + velocity.y + velocity.z) * 1000000.0).toLong())
                }
                """.trimIndent(),
                ((x.first + y.first + z.first + x.second + y.second + z.second) * 1000000.0).toLong(),
            ),
        )
}
