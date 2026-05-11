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

internal data class ComputeVmBenchmarkWorkloadSpec(
    val name: String,
    val scaleIterations: (Int) -> Int,
    val source: (Int) -> String,
    val runKotlinJvm: (Int) -> Int,
)

internal object ComputeVmBenchmarkWorkloads {
    val all: List<ComputeVmBenchmarkWorkloadSpec> =
        listOf(
            ComputeVmBenchmarkWorkloadSpec(
                name = "integer-mix",
                scaleIterations = { base -> base.coerceAtLeast(1) },
                source = ::integerMixSource,
                runKotlinJvm = ::runIntegerMix,
            ),
            ComputeVmBenchmarkWorkloadSpec(
                name = "function-mix",
                scaleIterations = { base -> base.coerceAtLeast(1) },
                source = ::functionMixSource,
                runKotlinJvm = ::runFunctionMix,
            ),
            ComputeVmBenchmarkWorkloadSpec(
                name = "branch-div",
                scaleIterations = { base -> (base / 4).coerceAtLeast(1) },
                source = ::branchDivSource,
                runKotlinJvm = ::runBranchDiv,
            ),
            ComputeVmBenchmarkWorkloadSpec(
                name = "recursive-fib",
                scaleIterations = { base -> (base / 5_000).coerceAtLeast(20) },
                source = ::recursiveFibSource,
                runKotlinJvm = ::runRecursiveFib,
            ),
        )

    private fun integerMixSource(iterations: Int): String =
        """
        fun integerMix(iterations: Int): Int {
            var state: Int = 305419896;
            var acc: Int = -1640531527;
            var i: Int = 0;
            while (i < iterations) {
                state = state * 1664525 + 1013904223;
                val x: Int = state ^ (state >> 16);
                acc = (acc + x) ^ (acc << 5);
                acc = acc + ((i * 31) ^ (x >> 3));
                i = i + 1;
            }
            return acc;
        }

        pub fun main(): Int {
            return integerMix($iterations);
        }
        """.trimIndent()

    private fun functionMixSource(iterations: Int): String =
        """
        fun mixA(value: Int, index: Int): Int {
            return ((value + index * 17) ^ (value << 3)) + (index >> 1);
        }

        fun mixB(value: Int, index: Int): Int {
            return ((value ^ (index * 131)) + (value >> 2)) ^ (index << 4);
        }

        fun functionMix(iterations: Int): Int {
            var acc: Int = 324508639;
            var i: Int = 0;
            while (i < iterations) {
                acc = mixB(mixA(acc, i), i);
                i = i + 1;
            }
            return acc;
        }

        pub fun main(): Int {
            return functionMix($iterations);
        }
        """.trimIndent()

    private fun branchDivSource(iterations: Int): String =
        """
        fun remainder(value: Int, divisor: Int): Int {
            return value - (value / divisor) * divisor;
        }

        fun branchDiv(iterations: Int): Int {
            var acc: Int = 7;
            var i: Int = 1;
            while (i < iterations + 1) {
                val mod: Int = remainder(i, 11);
                if (mod == 0) {
                    acc = acc + i / 3;
                } else {
                    if (mod < 5) {
                        acc = (acc ^ (i * 17)) + remainder(i, 7);
                    } else {
                        acc = acc - (i / (mod + 1)) + (acc << 1);
                    }
                }
                i = i + 1;
            }
            return acc;
        }

        pub fun main(): Int {
            return branchDiv($iterations);
        }
        """.trimIndent()

    private fun recursiveFibSource(iterations: Int): String =
        """
        fun remainder(value: Int, divisor: Int): Int {
            return value - (value / divisor) * divisor;
        }

        fun fib(value: Int): Int {
            if (value < 2) {
                return value;
            }
            return fib(value - 1) + fib(value - 2);
        }

        fun recursiveFib(iterations: Int): Int {
            var acc: Int = 0;
            var i: Int = 0;
            while (i < iterations) {
                val n: Int = 10 + remainder(i, 6);
                acc = acc + (fib(n) ^ (i * 31));
                i = i + 1;
            }
            return acc;
        }

        pub fun main(): Int {
            return recursiveFib($iterations);
        }
        """.trimIndent()

    private fun runIntegerMix(iterations: Int): Int {
        var state = 305_419_896
        var acc = -1_640_531_527
        var i = 0
        while (i < iterations) {
            state = state * 1_664_525 + 1_013_904_223
            val x = state xor (state shr 16)
            acc = (acc + x) xor (acc shl 5)
            acc += (i * 31) xor (x shr 3)
            i += 1
        }
        return acc
    }

    private fun runFunctionMix(iterations: Int): Int {
        var acc = 324_508_639
        var i = 0
        while (i < iterations) {
            acc = functionMixB(functionMixA(acc, i), i)
            i += 1
        }
        return acc
    }

    private fun functionMixA(
        value: Int,
        index: Int,
    ): Int = ((value + index * 17) xor (value shl 3)) + (index shr 1)

    private fun functionMixB(
        value: Int,
        index: Int,
    ): Int = ((value xor (index * 131)) + (value shr 2)) xor (index shl 4)

    private fun runBranchDiv(iterations: Int): Int {
        var acc = 7
        var i = 1
        while (i < iterations + 1) {
            val mod = i % 11
            acc =
                if (mod == 0) {
                    acc + i / 3
                } else if (mod < 5) {
                    (acc xor (i * 17)) + (i % 7)
                } else {
                    acc - (i / (mod + 1)) + (acc shl 1)
                }
            i += 1
        }
        return acc
    }

    private fun runRecursiveFib(iterations: Int): Int {
        var acc = 0
        var i = 0
        while (i < iterations) {
            val n = 10 + (i % 6)
            acc += recursiveFib(n) xor (i * 31)
            i += 1
        }
        return acc
    }

    private fun recursiveFib(value: Int): Int =
        if (value < 2) {
            value
        } else {
            recursiveFib(value - 1) + recursiveFib(value - 2)
        }
}
