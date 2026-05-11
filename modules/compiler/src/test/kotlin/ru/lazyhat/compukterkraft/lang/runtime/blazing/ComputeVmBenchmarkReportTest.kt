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
                            ckVmBestNanos = 50_000,
                            kotlinJvmBestNanos = 20_000,
                            pythonBestNanos = 40_000,
                            rustNativeBestNanos = 10_000,
                            ckVmMetrics =
                                NativeImageVmMetrics(
                                    executedInstructions = 1_000,
                                    valueClones = 300,
                                    registerReads = 500,
                                    registerWrites = 200,
                                    functionCalls = 10,
                                    functionReturns = 11,
                                    hostCallAttempts = 2,
                                    nativeHostCalls = 1,
                                    jvmHostCallSignals = 1,
                                    pauseSignals = 0,
                                    stringAllocations = 3,
                                    recordAllocations = 4,
                                    opcodeCounts = opcodeCounts(1 to 2, 6 to 1, 29 to 1, 30 to 2),
                                ),
                        ),
                        ComputeVmBenchmarkWorkloadReport(
                            workloadName = "function-mix",
                            iterations = 50,
                            checksum = 99,
                            ckVmBestNanos = 25_000,
                            kotlinJvmBestNanos = 10_000,
                            pythonBestNanos = 20_000,
                            rustNativeBestNanos = 5_000,
                            ckVmMetrics =
                                NativeImageVmMetrics(
                                    executedInstructions = 2_000,
                                    valueClones = 700,
                                    registerReads = 900,
                                    registerWrites = 400,
                                    functionCalls = 20,
                                    functionReturns = 21,
                                    hostCallAttempts = 0,
                                    nativeHostCalls = 0,
                                    jvmHostCallSignals = 0,
                                    pauseSignals = 1,
                                    stringAllocations = 0,
                                    recordAllocations = 0,
                                    opcodeCounts = opcodeCounts(1 to 3, 29 to 20, 30 to 21),
                                ),
                        ),
                    ),
            )

        assertEquals(
            """
            workload	iterations	checksum	samples	ck_vm_best_ns	kotlin_jvm_best_ns	python_best_ns	rust_native_best_ns	ck_vm_iters_per_sec	kotlin_jvm_iters_per_sec	python_iters_per_sec	rust_native_iters_per_sec	ck_vm_vs_kotlin_slowdown	ck_vm_vs_python_slowdown	ck_vm_vs_rust_slowdown	ck_vm_instructions	ck_vm_value_clones	ck_vm_register_reads	ck_vm_register_writes	ck_vm_function_calls	ck_vm_function_returns	ck_vm_host_call_attempts	ck_vm_native_host_calls	ck_vm_jvm_host_signals	ck_vm_pauses	ck_vm_string_allocations	ck_vm_record_allocations	ck_vm_opcode_counts
            integer-mix	100	-1234	3	50000	20000	40000	10000	2000000.000	5000000.000	2500000.000	10000000.000	2.500	1.250	5.000	1000	300	500	200	10	11	2	1	1	0	3	4	LoadConst=2,I32Add=1,CallStatic=1,Return=2
            function-mix	50	99	3	25000	10000	20000	5000	2000000.000	5000000.000	2500000.000	10000000.000	2.500	1.250	5.000	2000	700	900	400	20	21	0	0	0	1	0	0	LoadConst=3,CallStatic=20,Return=21
            """.trimIndent() + "\n",
            report.toTsv(),
        )

        val markdown = report.toMarkdown()

        assertContains(markdown, "# CKL Compute VM Benchmark")
        assertContains(
            markdown,
            "| integer-mix | 100 | -1234 | 2,000,000.000 | 5,000,000.000 | 2,500,000.000 | 10,000,000.000 | 2.500x | 1.250x | 5.000x |",
        )
        assertContains(
            markdown,
            "| function-mix | 50 | 99 | 2,000,000.000 | 5,000,000.000 | 2,500,000.000 | 10,000,000.000 | 2.500x | 1.250x | 5.000x |",
        )
        assertContains(markdown, "## CK VM Internal Counters")
        assertContains(
            markdown,
            "| integer-mix | 1,000 | 300 | 500 | 200 | 10 | 11 | 2 | 1 | 1 | 0 | 3 | 4 | LoadConst=2,I32Add=1,CallStatic=1,Return=2 |",
        )
        assertContains(
            markdown,
            "| function-mix | 2,000 | 700 | 900 | 400 | 20 | 21 | 0 | 0 | 0 | 1 | 0 | 0 | LoadConst=3,CallStatic=20,Return=21 |",
        )
    }

    private fun opcodeCounts(vararg counts: Pair<Int, Long>): List<Long> {
        val values = MutableList(NativeImageVmMetrics.OPCODE_COUNT_SIZE) { 0L }
        counts.forEach { (opcode, count) -> values[opcode] = count }
        return values
    }
}
