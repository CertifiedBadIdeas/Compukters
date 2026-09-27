/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.worker.k2

import ru.lazyhat.compukters.compiler.artifact.model.Manifest
import ru.lazyhat.compukters.compiler.artifact.read.ArtifactReader
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriteResult
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriter

internal fun collectionBenchmarkCases(): List<CollectionBenchmarkCase> =
    listOf(1024, 4096).flatMap { count ->
        listOf("indexed", "iteration", "update", "pipeline", "search").flatMap { workload ->
            listOf("scalar", "boxed", "bridge").map { representation ->
                CollectionBenchmarkCase(representation, workload, count, 8)
            }
        }
    }

internal data class CollectionBenchmarkCase(
    val representation: String,
    val workload: String,
    val count: Int,
    val rounds: Int,
) {
    val id: String get() = "$workload-$count-$representation"

    fun checksum(): Int {
        var total = 0
        for (round in 0 until rounds) {
            if (workload == "search") {
                total += count - 2
            } else {
                for (index in 0 until count) {
                    val value = 1000 + index
                    total +=
                        when (workload) {
                            "update" -> value + round + 1
                            "pipeline" -> if ((value + 1) % 2 == 0) value + 1 else 0
                            else -> value
                        }
                }
            }
        }
        return total
    }

    fun source(): String {
        val storedType = if (representation == "boxed") "Any" else "Int"
        val viewType = if (representation == "scalar") "Int" else "Any"

        fun read(value: String): String = if (viewType == "Int") value else "($value as Int)"
        val body =
            when (workload) {
                "indexed" -> {
                    """
                var index = 0
                while (index < view.size) {
                    checksum += ${read("view[index]")}
                    index += 1
                }
            """
                }

                "iteration" -> {
                    "for (value in view) checksum += ${read("value")}"
                }

                "update" -> {
                    """
                var index = 0
                while (index < view.size) {
                    val next = ${read("view[index]")} + 1
                    data[index] = next
                    checksum += next
                    index += 1
                }
            """
                }

                "pipeline" -> {
                    val transform = if (representation == "boxed") "(${read("value")} + 1) as Any" else "${read("value")} + 1"
                    val resultRead = if (representation == "boxed") "(value as Int)" else "value"
                    """
                    val mapped = view.map { value -> $transform }
                    val selected = mapped.filter { value -> $resultRead % 2 == 0 }
                    for (value in selected) checksum += $resultRead
                """
                }

                "search" -> {
                    "checksum += view.indexOf(${1000 + count - 1}) + view.indexOf(-1)"
                }

                else -> {
                    error("Unknown workload: $workload")
                }
            }
        return """
            import kotlin.collections.*
            fun main() {
                val data = ArrayList<$storedType>($count)
                var item = 0
                while (item < $count) {
                    data.add(1000 + item)
                    item += 1
                }
                val view: List<$viewType> = data
                println("ready")
                var checksum = 0
                var round = 0
                while (round < $rounds) {
                    ${body.trimIndent()}
                    round += 1
                }
                println(checksum)
            }
            """.trimIndent()
    }
}

/** Remove only the compiler's 64 KiB admission floor from measurement copies, preserving executable code. */
internal fun collectionBenchmarkArtifact(bytes: ByteArray): ByteArray {
    val artifact = ArtifactReader.read(bytes)
    val m = artifact.manifest
    val result =
        ArtifactWriter.write(
            artifact.copy(
                manifest =
                    Manifest(
                        requiredHeapBytes = 0u,
                        requiredStackBytes = m.requiredStackBytes,
                        maximumCoroutines = m.maximumCoroutines,
                        maximumCallDepth = m.maximumCallDepth,
                        maximumHostRequests = m.maximumHostRequests,
                        maximumEvents = m.maximumEvents,
                        maximumBlockCost = m.maximumBlockCost,
                        minimumSliceCost = m.minimumSliceCost,
                        compilerAbi = m.compilerAbi,
                        platformAbi = m.platformAbi,
                        maximumChannels = m.maximumChannels,
                        maximumChannelValues = m.maximumChannelValues,
                    ),
            ),
        )
    check(result is ArtifactWriteResult.Success) { result.toString() }
    return result.bytes
}
