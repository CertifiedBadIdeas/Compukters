/*
 * The Compukter Kraft Developers
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#include "kernel.h"

int main(int argc, char **argv) {
    if (argc != 4) {
        fputs("usage: native-kernel ITERATIONS SEED BATCH\n", stderr);
        return 2;
    }

    uint32_t iterations = (uint32_t)strtoul(argv[1], NULL, 0);
    uint32_t seed = (uint32_t)strtoul(argv[2], NULL, 0);
    uint32_t batch = (uint32_t)strtoul(argv[3], NULL, 0);
    volatile uint32_t runtime_iterations = iterations;
    volatile uint32_t runtime_seed = seed;
    volatile uint32_t sink = 0u;

    for (uint32_t index = 0; index < batch; ++index) {
        sink = benchmark_kernel(runtime_iterations, runtime_seed);
        runtime_iterations = iterations;
        runtime_seed = seed;
    }

    printf("CK_RESULT\t%08" PRIx32 "\n", sink);
    return 0;
}
