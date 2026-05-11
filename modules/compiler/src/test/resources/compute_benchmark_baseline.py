#!/usr/bin/env python3
#
# The Compukter Kraft Developers
#
# Copyright (C) 2026 Vsevolod Petrov (lazyhat)
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.

from __future__ import annotations

import sys
import time
from collections.abc import Callable


INT_MASK = 0xFFFFFFFF
INT_SIGN = 0x80000000


def i32(value: int) -> int:
    value &= INT_MASK
    if value & INT_SIGN:
        return value - 0x100000000
    return value


def shl(value: int, bits: int) -> int:
    return i32(i32(value) << bits)


def integer_mix(iterations: int) -> int:
    state = 305_419_896
    acc = -1_640_531_527
    i = 0
    while i < iterations:
        state = i32(state * 1_664_525 + 1_013_904_223)
        x = i32(state ^ (state >> 16))
        acc = i32(i32(acc + x) ^ shl(acc, 5))
        acc = i32(acc + i32(i32(i * 31) ^ (x >> 3)))
        i += 1
    return acc


def function_mix(iterations: int) -> int:
    acc = 324_508_639
    i = 0
    while i < iterations:
        acc = function_mix_b(function_mix_a(acc, i), i)
        i += 1
    return acc


def function_mix_a(value: int, index: int) -> int:
    return i32(i32(i32(value + i32(index * 17)) ^ shl(value, 3)) + (index >> 1))


def function_mix_b(value: int, index: int) -> int:
    return i32(i32(i32(value ^ i32(index * 131)) + (value >> 2)) ^ shl(index, 4))


def branch_div(iterations: int) -> int:
    acc = 7
    i = 1
    while i < iterations + 1:
        modulo = i % 11
        if modulo == 0:
            acc = i32(acc + i // 3)
        elif modulo < 5:
            acc = i32(i32(acc ^ i32(i * 17)) + (i % 7))
        else:
            acc = i32(i32(acc - (i // (modulo + 1))) + shl(acc, 1))
        i += 1
    return acc


def recursive_fib_workload(iterations: int) -> int:
    acc = 0
    i = 0
    while i < iterations:
        n = 10 + (i % 6)
        acc = i32(acc + i32(recursive_fib(n) ^ i32(i * 31)))
        i += 1
    return acc


def recursive_fib(value: int) -> int:
    if value < 2:
        return value
    return i32(recursive_fib(value - 1) + recursive_fib(value - 2))


WORKLOADS: dict[str, Callable[[int], int]] = {
    "integer-mix": integer_mix,
    "function-mix": function_mix,
    "branch-div": branch_div,
    "recursive-fib": recursive_fib_workload,
}


def main() -> None:
    workload_name = sys.argv[1]
    iterations = int(sys.argv[2])
    samples = int(sys.argv[3])

    workload = WORKLOADS[workload_name]
    checksum = None
    best_nanos = None
    for _ in range(samples):
        started = time.perf_counter_ns()
        sample_checksum = workload(iterations)
        elapsed = time.perf_counter_ns() - started
        if checksum is None:
            checksum = sample_checksum
        elif checksum != sample_checksum:
            raise RuntimeError(f"{workload_name} checksum changed between samples: {checksum} != {sample_checksum}")
        best_nanos = elapsed if best_nanos is None else min(best_nanos, elapsed)

    print("checksum\tbest_nanos")
    print(f"{checksum}\t{best_nanos}")


if __name__ == "__main__":
    main()
