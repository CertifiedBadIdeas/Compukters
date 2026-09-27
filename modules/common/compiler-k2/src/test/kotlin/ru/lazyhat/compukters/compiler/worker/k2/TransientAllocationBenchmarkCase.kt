/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.worker.k2

internal fun transientAllocationBenchmarkCases(): List<TransientAllocationBenchmarkCase> =
    listOf("while-indexed", "for-list", "fold", "fold-val", "fold-read-var", "fold-write-var", "fold-ref", "fold-prebuilt")
        .map(::TransientAllocationBenchmarkCase)

internal data class TransientAllocationBenchmarkCase(
    val workload: String,
) {
    val count = 256
    val rounds = 300
    val id: String get() = "$workload-$count-$rounds"

    fun checksum(): Int = rounds * (count * 1001 + count * (count - 1) / 2) + count * 1000 + count * (count - 1) / 2

    fun source(): String {
        val setup =
            when (workload) {
                "fold-write-var" -> "var bias = 0"
                "fold-ref" -> "val bias = Bias(1)"
                "fold-prebuilt" -> "val operation: (Int, Int) -> Int = { sum, item -> sum + item + 1 }"
                else -> ""
            }
        val operation =
            when (workload) {
                "while-indexed" -> {
                    """
                    var sum = 0
                    index = 0
                    while (index < data.size) { sum += data[index] + 1; index += 1 }
                    checksum += sum
                    """.trimIndent()
                }

                "for-list" -> {
                    """
                    var sum = 0
                    for (item in data) { sum += item + 1 }
                    checksum += sum
                    """.trimIndent()
                }

                "fold" -> {
                    "checksum += data.fold(0) { sum, item -> sum + item + 1 }"
                }

                "fold-val" -> {
                    """
                    val bias = 1
                    checksum += data.fold(0) { sum, item -> sum + item + bias }
                    """.trimIndent()
                }

                "fold-read-var" -> {
                    """
                    var bias = 1
                    checksum += data.fold(0) { sum, item -> sum + item + bias }
                    """.trimIndent()
                }

                "fold-write-var" -> {
                    """
                    bias = 1
                    val operation: (Int, Int) -> Int = { sum, item -> sum + item + bias }
                    checksum += data.fold(0, operation)
                    bias = 2
                    require(operation(0, 0) == 2)
                    """.trimIndent()
                }

                "fold-ref" -> {
                    "checksum += data.fold(0) { sum, item -> sum + item + bias.value }"
                }

                "fold-prebuilt" -> {
                    "checksum += data.fold(0, operation)"
                }

                else -> {
                    error("Unknown workload: $workload")
                }
            }
        return """
            import kotlin.collections.*
            class Bias(var value: Int)
            fun main() {
                val storage = ArrayList<Int>($count)
                var index = 0
                while (index < $count) { storage.add(1000 + index); index += 1 }
                val data: List<Int> = storage
                $setup
                println("ready")
                var checksum = 0
                var round = 0
                while (round < $rounds) {
                    $operation
                    round += 1
                }
                index = 0
                while (index < data.size) { checksum += data[index]; index += 1 }
                println(checksum)
            }
            """.trimIndent()
    }
}
