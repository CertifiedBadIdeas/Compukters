/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.worker.k2

internal fun sequenceBenchmarkCases(): List<SequenceBenchmarkCase> =
    listOf(32, 4096).flatMap { count ->
        listOf("full", "take8", "first").flatMap { workload ->
            listOf("eager", "lazy").map { representation ->
                SequenceBenchmarkCase(representation, workload, count, 8)
            }
        }
    }

internal data class SequenceBenchmarkCase(
    val representation: String,
    val workload: String,
    val count: Int,
    val rounds: Int,
) {
    val id: String get() = "$workload-$count-$representation"

    fun checksum(): Int {
        val values = (0 until count).map { 1000 + it }.filter { it % 2 == 0 }.map { it * 2 }
        return rounds *
            when (workload) {
                "full" -> values.sum()
                "take8" -> values.take(8).sum()
                "first" -> values.first()
                else -> error("Unknown workload $workload")
            }
    }

    fun source(): String {
        val chain = if (representation == "lazy") "data.asSequence()" else "data"
        val selection = "$chain.filter { it % 2 == 0 }.map { it * 2 }"
        val body =
            when (workload) {
                "full" -> {
                    val result = if (representation == "lazy") "$selection.toList()" else selection
                    "val result = $result\nfor (value in result) checksum += value"
                }

                "take8" -> {
                    if (representation == "lazy") {
                        "val result = $selection.take(8).toList()\nfor (value in result) checksum += value"
                    } else {
                        "val result = $selection\nvar i = 0\nwhile (i < 8) { checksum += result[i]; i += 1 }"
                    }
                }

                "first" -> {
                    "checksum += $selection.firstOrNull() ?: 0"
                }

                else -> {
                    error("Unknown workload $workload")
                }
            }
        return """
            import kotlin.collections.*
            import kotlin.sequences.*
            fun main() {
                val data = ArrayList<Int>($count)
                var index = 0
                while (index < $count) { data.add(1000 + index); index += 1 }
                println("ready")
                var checksum = 0
                var round = 0
                while (round < $rounds) {
                    ${body.prependIndent("    ").trimStart()}
                    round += 1
                }
                println(checksum)
            }
            """.trimIndent()
    }
}
