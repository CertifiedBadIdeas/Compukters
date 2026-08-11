/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#ifndef COMPUKTER_KRAFT_RV32_C_COMPARISON_KERNEL_H
#define COMPUKTER_KRAFT_RV32_C_COMPARISON_KERNEL_H

#include <stdint.h>

#define CK_COMPUTE_ROUNDS 8u
#define CK_BRANCH_ROUNDS 4u
#define CK_ARRAY_WORDS 64u
#define CK_COPY_BYTES 256u
#define CK_GEOMETRY_POINTS 8u
#define CK_ORACLE_ITERATIONS 1000u
#define CK_ORACLE_SEED 0x12345678u
#define CK_ORACLE_CHECKSUM 3993320792u

uint32_t benchmark_kernel(uint32_t iterations, uint32_t seed);

#endif
