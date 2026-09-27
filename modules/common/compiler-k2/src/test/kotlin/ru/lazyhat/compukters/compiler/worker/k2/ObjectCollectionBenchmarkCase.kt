/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.worker.k2

internal fun objectCollectionBenchmarkCases(): List<ObjectCollectionBenchmarkCase> =
    listOf("sized", "growing").flatMap { storage ->
        listOf("indexed", "map", "filter-none", "filter-all", "pipeline").map { workload ->
            ObjectCollectionBenchmarkCase(storage, workload)
        }
    }

internal data class ObjectCollectionBenchmarkCase(
    val storage: String,
    val workload: String,
) {
    val count: Int = 4096
    val id: String get() = "$workload-$count-list-$storage"

    fun checksum(): Int {
        var result = 0
        for (index in 0 until count) {
            val value = 2001 + index * 2
            result += value // Original input is retained until the final scan.
            result +=
                when (workload) {
                    "filter-none" -> 0
                    "map" -> value + 1
                    "pipeline" -> if (index % 2 == 0) value + 1 else 0
                    else -> value
                }
        }
        return result
    }

    fun source(): String {
        val operation =
            when (workload) {
                "indexed" -> {
                    "val result: List<Cell> = data"
                }

                "map" -> {
                    "val result = data.map { cell -> Cell(cell.x + 1, cell.y) }; require(result[0] !== data[0])"
                }

                "filter-none" -> {
                    "val result = data.filter { cell -> cell.x < 0 }; require(result.size == 0)"
                }

                "filter-all" -> {
                    "val result = data.filter { cell -> cell.x >= 0 }; require(result[0] === data[0])"
                }

                "pipeline" -> {
                    """
                    val result = data.map { cell -> Cell(cell.x + 1, cell.y) }
                        .filter { cell -> (cell.x - 1001) % 2 == 0 }
                    require(result[0] !== data[0])
                    """.trimIndent()
                }

                else -> {
                    error("Unknown workload: $workload")
                }
            }
        return """
            import kotlin.collections.*
            class Cell(var x: Int, var y: Int)
            fun main() {
                val data = ArrayList<Cell>(${if (storage == "sized") count else 10})
                var index = 0
                while (index < $count) {
                    data.add(Cell(1000 + index, 1001 + index))
                    index += 1
                }
                println("ready")
                $operation
                var checksum = 0
                index = 0
                while (index < result.size) {
                    checksum += result[index].x + result[index].y
                    index += 1
                }
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
