/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.compukterkraft.lang.runtime.blazing

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ComputeVmBenchmarkReportTest {
    @Test
    fun reportIncludesStableTsvAndMarkdownSummary() {
        val report =
            ComputeVmBenchmarkReport(
                samples = 3,
                workloads =
                    listOf(
                        ComputeVmBenchmarkWorkloadReport(
                            workloadName = "integer-mix",
                            iterations = 100,
                            checksum = -1_234,
                            lowVmBestNanos = 12_500,
                            kotlinJvmBestNanos = 20_000,
                            pythonBestNanos = 40_000,
                            rustNativeBestNanos = 10_000,
                            lowVmMetrics =
                                NativeLowImageVmMetrics(
                                    executedInstructions = 500,
                                    functionCalls = 2,
                                    functionReturns = 3,
                                    memoryLoads = 1,
                                    memoryStores = 1,
                                    opcodeCounts =
                                        List(NativeLowImageVmMetrics.OPCODE_COUNT_SIZE) { opcode ->
                                            when (opcode) {
                                                6 -> 100L
                                                20 -> 3L
                                                else -> 0L
                                            }
                                        },
                                ),
                        ),
                        ComputeVmBenchmarkWorkloadReport(
                            workloadName = "function-mix",
                            iterations = 50,
                            checksum = 99,
                            lowVmBestNanos = 12_500,
                            kotlinJvmBestNanos = 10_000,
                            pythonBestNanos = 20_000,
                            rustNativeBestNanos = 5_000,
                            lowVmMetrics =
                                NativeLowImageVmMetrics(
                                    executedInstructions = 250,
                                    functionCalls = 10,
                                    functionReturns = 11,
                                    pauseSignals = 2,
                                    opcodeCounts =
                                        List(NativeLowImageVmMetrics.OPCODE_COUNT_SIZE) { opcode ->
                                            when (opcode) {
                                                19 -> 10L
                                                20 -> 11L
                                                else -> 0L
                                            }
                                        },
                                ),
                        ),
                    ),
            )

        assertEquals(
            """
            workload	iterations	checksum	samples	low_vm_best_ns	kotlin_jvm_best_ns	python_best_ns	rust_native_best_ns	low_vm_instructions	low_vm_ns_per_instruction	low_vm_instructions_per_iteration	low_vm_function_calls	low_vm_function_returns	low_vm_pauses	low_vm_memory_loads	low_vm_memory_stores	low_vm_opcode_counts	low_vm_iters_per_sec	kotlin_jvm_iters_per_sec	python_iters_per_sec	rust_native_iters_per_sec	low_vm_vs_kotlin_slowdown	low_vm_vs_python_slowdown	low_vm_vs_rust_slowdown
            integer-mix	100	-1234	3	12500	20000	40000	10000	500	25.000	5.000	2	3	0	1	1	I32Add=100,Return=3	8000000.000	5000000.000	2500000.000	10000000.000	0.625	0.313	1.250
            function-mix	50	99	3	12500	10000	20000	5000	250	50.000	5.000	10	11	2	0	0	CallStatic=10,Return=11	4000000.000	5000000.000	2500000.000	10000000.000	1.250	0.625	2.500
            """.trimIndent() + "\n",
            report.toTsv(),
        )

        val markdown = report.toMarkdown()

        assertContains(markdown, "# CKL Compute VM Benchmark")
        assertContains(
            markdown,
            "| integer-mix | 100 | -1234 | 8,000,000.000 | 5,000,000.000 | 2,500,000.000 | 10,000,000.000 | 0.625x | 0.313x | 1.250x |",
        )
        assertContains(
            markdown,
            "| function-mix | 50 | 99 | 4,000,000.000 | 5,000,000.000 | 2,500,000.000 | 10,000,000.000 | 1.250x | 0.625x | 2.500x |",
        )
        assertContains(markdown, "## Low VM Metrics")
        assertContains(
            markdown,
            "| integer-mix | 500 | 25.000 | 5.000 | 2 | 3 | 0 | 1 | 1 | I32Add=100,Return=3 |",
        )
        assertContains(
            markdown,
            "| function-mix | 250 | 50.000 | 5.000 | 10 | 11 | 2 | 0 | 0 | CallStatic=10,Return=11 |",
        )
    }
}
