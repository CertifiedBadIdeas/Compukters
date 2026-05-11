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
                        ),
                        ComputeVmBenchmarkWorkloadReport(
                            workloadName = "function-mix",
                            iterations = 50,
                            checksum = 99,
                            ckVmBestNanos = 25_000,
                            kotlinJvmBestNanos = 10_000,
                            pythonBestNanos = 20_000,
                            rustNativeBestNanos = 5_000,
                        ),
                    ),
            )

        assertEquals(
            """
            workload	iterations	checksum	samples	ck_vm_best_ns	kotlin_jvm_best_ns	python_best_ns	rust_native_best_ns	ck_vm_iters_per_sec	kotlin_jvm_iters_per_sec	python_iters_per_sec	rust_native_iters_per_sec	ck_vm_vs_kotlin_slowdown	ck_vm_vs_python_slowdown	ck_vm_vs_rust_slowdown
            integer-mix	100	-1234	3	50000	20000	40000	10000	2000000.000	5000000.000	2500000.000	10000000.000	2.500	1.250	5.000
            function-mix	50	99	3	25000	10000	20000	5000	2000000.000	5000000.000	2500000.000	10000000.000	2.500	1.250	5.000
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
    }
}
