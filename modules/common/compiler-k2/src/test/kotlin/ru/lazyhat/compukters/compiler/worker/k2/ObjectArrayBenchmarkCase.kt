/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.worker.k2

internal fun objectArrayBenchmarkCases(): List<ObjectArrayBenchmarkCase> =
    listOf(1024, 4096).flatMap { count ->
        listOf(2, 3).flatMap { fields ->
            listOf("indexed", "update", "shallow", "deep", "pipeline").flatMap { workload ->
                val representations = if (workload == "shallow") listOf("objects") else listOf("objects", "packed")
                representations.map { ObjectArrayBenchmarkCase(it, workload, count, fields, 2) }
            }
        }
    }

internal data class ObjectArrayBenchmarkCase(
    val representation: String,
    val workload: String,
    val count: Int,
    val fields: Int,
    val rounds: Int,
) {
    val id: String get() = "$workload-$count-${fields}fields-$representation"

    fun checksum(): Int {
        var result = 0
        for (round in 0 until rounds) {
            for (index in 0 until count) {
                if (workload != "pipeline" || index % 2 == 0) {
                    for (field in 0 until fields) result += 1000 + index + field
                    result +=
                        when (workload) {
                            "update" -> round + 1
                            "deep", "pipeline" -> 1
                            else -> 0
                        }
                }
            }
        }
        // Reading every input field after the operation keeps the original storage live.
        for (index in 0 until count) {
            for (field in 0 until fields) result += 1000 + index + field
            if (workload == "update") result += rounds
        }
        return result
    }

    fun source(): String {
        val objectStorage = representation == "objects"

        fun allocate(
            name: String,
            size: Int,
        ): String =
            if (objectStorage) {
                "val $name = arrayOfNulls<Cell>($size)"
            } else {
                (0 until fields).joinToString("\n") { "val ${name}$it = IntArray($size)" }
            }

        fun read(
            name: String,
            index: String,
            field: Int,
        ): String = if (objectStorage) "($name[$index] as Cell).f$field" else "${name}$field[$index]"

        fun sum(name: String): String = (0 until fields).joinToString("\n") { "checksum += ${read(name, "index", it)}" }

        fun copy(
            name: String,
            index: String,
            original: String,
            originalIndex: String,
            increment: Int,
        ): String =
            if (objectStorage) {
                val args =
                    (0 until fields).joinToString(
                        ", ",
                    ) { "${read(original, originalIndex, it)}${if (it == 0) " + $increment" else ""}" }
                "$name[$index] = Cell($args)"
            } else {
                (0 until fields).joinToString("\n") {
                    "${name}$it[$index] = ${read(original, originalIndex, it)}${if (it == 0) " + $increment" else ""}"
                }
            }

        fun loop(
            size: Int,
            body: String,
        ): String =
            """
            index = 0
            while (index < $size) {
                $body
                index += 1
            }
            """.trimIndent()

        val initialize =
            if (objectStorage) {
                "data[index] = Cell(${(0 until fields).joinToString(", ") { "1000 + index + $it" }})"
            } else {
                (0 until fields).joinToString("\n") { "data$it[index] = 1000 + index + $it" }
            }
        val body =
            when (workload) {
                "indexed" -> {
                    loop(count, sum("data"))
                }

                "update" -> {
                    val mutation = "${read("data", "index", 0)} = ${read("data", "index", 0)} + 1"
                    loop(count, "$mutation\n${sum("data")}") +
                        if (objectStorage) "\nrequire(alias.f0 == 1000 + round + 1 && alias === data[0])" else ""
                }

                "shallow" -> {
                    """
                    ${allocate("copied", count)}
                    ${loop(count, "copied[index] = data[index]")}
                    require(copied[0] === data[0])
                    ${loop(count, sum("copied"))}
                    """.trimIndent()
                }

                "deep" -> {
                    """
                    ${allocate("copied", count)}
                    ${loop(count, copy("copied", "index", "data", "index", 1))}
                    ${if (objectStorage) "require(copied[0] !== data[0])" else ""}
                    ${loop(count, sum("copied"))}
                    """.trimIndent()
                }

                "pipeline" -> {
                    // Map to new records, then retain even-index records in a second array.
                    val select =
                        if (objectStorage) {
                            "selected[index] = mapped[index * 2]"
                        } else {
                            (0 until fields).joinToString("\n") { "selected$it[index] = mapped$it[index * 2]" }
                        }
                    """
                    ${allocate("mapped", count)}
                    ${loop(count, copy("mapped", "index", "data", "index", 1))}
                    ${allocate("selected", count / 2)}
                    ${loop(count / 2, select)}
                    ${if (objectStorage) "require(selected[0] === mapped[0] && selected[0] !== data[0])" else ""}
                    ${loop(count / 2, sum("selected"))}
                    """.trimIndent()
                }

                else -> {
                    error("Unknown workload: $workload")
                }
            }
        return """
            ${if (objectStorage) "class Cell(${(0 until fields).joinToString(", ") { "var f$it: Int" }})" else ""}
            fun main() {
                ${allocate("data", count)}
                var index = 0
                ${loop(count, initialize)}
                ${if (objectStorage && workload == "update") "val alias = data[0] as Cell" else ""}
                println("ready")
                var checksum = 0
                var round = 0
                while (round < $rounds) {
                    $body
                    round += 1
                }
                ${loop(count, sum("data"))}
                println(checksum)
            }
            """.trimIndent()
    }
}
