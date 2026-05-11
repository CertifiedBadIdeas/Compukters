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
                                    runInvocations = 1,
                                    elapsedNanos = 11_000,
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
                                    runInvocations = 3,
                                    elapsedNanos = 12_000,
                                    pauseSignals = 2,
                                ),
                        ),
                    ),
            )

        assertEquals(
            """
            workload	iterations	checksum	samples	low_vm_best_ns	low_vm_elapsed_ns	low_vm_run_invocations	low_vm_pauses	kotlin_jvm_best_ns	python_best_ns	rust_native_best_ns	low_vm_iters_per_sec	kotlin_jvm_iters_per_sec	python_iters_per_sec	rust_native_iters_per_sec	low_vm_vs_kotlin_slowdown	low_vm_vs_python_slowdown	low_vm_vs_rust_slowdown
            integer-mix	100	-1234	3	12500	11000	1	0	20000	40000	10000	8000000.000	5000000.000	2500000.000	10000000.000	0.625	0.313	1.250
            function-mix	50	99	3	12500	12000	3	2	10000	20000	5000	4000000.000	5000000.000	2500000.000	10000000.000	1.250	0.625	2.500
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
        assertContains(markdown, "## Low VM Runtime")
        assertContains(
            markdown,
            "| integer-mix | 11,000 | 1 | 0 |",
        )
        assertContains(
            markdown,
            "| function-mix | 12,000 | 3 | 2 |",
        )
    }
}
