/*
 * The Compukter Kraft Developers
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include <stdint.h>

#include "kernel.h"

#define CONTROL_STATUS (*(volatile uint32_t *)0x10000000u)
#define CONTROL_EXIT_CODE (*(volatile uint32_t *)0x10000008u)

extern const uint32_t ck_batch_value;

__attribute__((noreturn)) void platform_main(void) {
    uint32_t batch = ck_batch_value;
    uint32_t checksum = 0u;
    volatile uint32_t runtime_seed = CK_ORACLE_SEED;
    volatile uint32_t sink = 0u;

    for (uint32_t index = 0; index < batch; ++index) {
        checksum = benchmark_kernel(CK_ORACLE_ITERATIONS, runtime_seed);
        sink = checksum;
        runtime_seed = CK_ORACLE_SEED;
    }

    CONTROL_EXIT_CODE = sink;
    CONTROL_STATUS = 3u;
    for (;;) {
    }
}
