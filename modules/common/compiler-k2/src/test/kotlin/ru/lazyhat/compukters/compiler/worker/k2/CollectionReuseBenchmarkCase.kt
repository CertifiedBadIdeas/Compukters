/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.worker.k2

internal fun collectionReuseBenchmarkCases(): List<CollectionReuseBenchmarkCase> =
    listOf("fresh", "reuse-list", "reuse-objects").map { CollectionReuseBenchmarkCase(it) }

internal data class CollectionReuseBenchmarkCase(
    val storage: String,
) {
    val workload: String = "map-not-null"
    val count: Int = 1024
    val rounds: Int = 100
    val id: String get() = "map-not-null-$count-$rounds-$storage"

    fun checksum(): Int {
        var checksum = 0
        for (round in 0 until rounds) {
            for (index in 0 until count step 2) checksum += 2001 + index * 2 + round + 1
        }
        for (index in 0 until count) checksum += 2001 + index * 2
        return checksum
    }

    fun source(): String {
        val setup =
            when (storage) {
                "fresh" -> {
                    "var result: List<Cell> = emptyList<Cell>()"
                }

                "reuse-list" -> {
                    "val result = ArrayList<Cell>(${count / 2})"
                }

                "reuse-objects" -> {
                    """
                    val pool = ArrayList<Cell>(${count / 2})
                    index = 0
                    while (index < ${count / 2}) { pool.add(Cell(0, 0)); index += 1 }
                    val result = ArrayList<Cell>(${count / 2})
                    """.trimIndent()
                }

                else -> {
                    error("Unknown storage: $storage")
                }
            }
        val operation =
            when (storage) {
                "fresh" -> {
                    """
                    result = data.mapNotNull { cell ->
                        if ((cell.x - 1000) % 2 == 0) Cell(cell.x + round + 1, cell.y) else null
                    }
                    """.trimIndent()
                }

                "reuse-list" -> {
                    """
                    result.clear()
                    data.mapNotNullTo(result) { cell ->
                        if ((cell.x - 1000) % 2 == 0) Cell(cell.x + round + 1, cell.y) else null
                    }
                    """.trimIndent()
                }

                "reuse-objects" -> {
                    """
                    result.clear()
                    var position = 0
                    data.mapNotNullTo(result) { cell ->
                        if ((cell.x - 1000) % 2 == 0) {
                            val mapped = pool[position]
                            position += 1
                            mapped.x = cell.x + round + 1
                            mapped.y = cell.y
                            mapped
                        } else null
                    }
                    """.trimIndent()
                }

                else -> {
                    error("Unknown storage: $storage")
                }
            }
        return """
            import kotlin.collections.*
            class Cell(var x: Int, var y: Int)
            fun main() {
                val data = ArrayList<Cell>($count)
                var index = 0
                while (index < $count) {
                    data.add(Cell(1000 + index, 1001 + index))
                    index += 1
                }
                $setup
                println("ready")
                var round = 0
                var checksum = 0
                while (round < $rounds) {
                    $operation
                    require(result.size == ${count / 2})
                    require(result[0].x == 1001 + round)
                    index = 0
                    while (index < result.size) {
                        checksum += result[index].x + result[index].y
                        index += 1
                    }
                    round += 1
                }
                require(result[0] !== data[0])
                ${if (storage == "reuse-objects") "require(result[0] === pool[0] && result[${count / 2 - 1}] === pool[${count / 2 - 1}])" else ""}
                index = 0
                while (index < data.size) {
                    checksum += data[index].x + data[index].y
                    index += 1
                }
                println(checksum)
            }
            """.trimIndent()
    }
}
