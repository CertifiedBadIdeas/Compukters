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

#include "kernel.h"

static uint32_t rotate_left(uint32_t value, uint32_t shift) {
    shift &= 31u;
    return (value << shift) | (value >> ((32u - shift) & 31u));
}

uint32_t benchmark_kernel(uint32_t iterations, uint32_t seed) {
    uint32_t left[CK_ARRAY_WORDS];
    uint32_t right[CK_ARRAY_WORDS];
    uint32_t mixed[CK_ARRAY_WORDS];
    uint8_t source[CK_COPY_BYTES];
    uint8_t destination[CK_COPY_BYTES];
    uint32_t state = seed ^ 0x9e3779b9u;
    uint32_t checksum = 0x6a09e667u;

    for (uint32_t index = 0; index < CK_ARRAY_WORDS; ++index) {
        state = state * 1664525u + 1013904223u;
        left[index] = state ^ (index * 0x45d9f3bu);
        state = state * 22695477u + 1u;
        right[index] = rotate_left(state, index);
        mixed[index] = 0u;
    }
    for (uint32_t index = 0; index < CK_COPY_BYTES; ++index) {
        source[index] = (uint8_t)(rotate_left(state + index * 17u, index) >> 24u);
        destination[index] = 0u;
    }

    for (uint32_t iteration = 0; iteration < iterations; ++iteration) {
        for (uint32_t round = 0; round < CK_COMPUTE_ROUNDS; ++round) {
            state ^= rotate_left(state * 0x85ebca6bu + iteration, round + 5u);
            state = state * 0xc2b2ae35u + 0x27d4eb2fu;
            checksum ^= rotate_left(state + checksum, round + iteration);
        }

        for (uint32_t round = 0; round < CK_BRANCH_ROUNDS; ++round) {
            uint32_t selector = rotate_left(state ^ checksum, round + 1u);
            if ((selector & 3u) == 0u) {
                checksum += selector ^ 0xa5a5a5a5u;
            } else if ((selector & 3u) == 1u) {
                checksum = rotate_left(checksum, 7u) ^ selector;
            } else if ((selector & 3u) == 2u) {
                checksum -= selector * 33u;
            } else {
                checksum ^= selector + 0x3c6ef372u;
            }
            state += checksum ^ round;
        }

        for (uint32_t index = 0; index < CK_ARRAY_WORDS; ++index) {
            mixed[index] = left[index] * 33u + right[index] * 17u + iteration;
        }
        for (uint32_t index = 0; index < CK_ARRAY_WORDS; ++index) {
            checksum += rotate_left(mixed[index], index);
        }
        for (uint32_t probe = 0; probe < CK_ARRAY_WORDS; ++probe) {
            state = state * 1664525u + 1013904223u;
            checksum ^= mixed[(state >> 24u) & (CK_ARRAY_WORDS - 1u)];
        }

        for (uint32_t index = 0; index < CK_COPY_BYTES; ++index) {
            destination[index] = source[index];
            checksum += (uint32_t)destination[index] * (index + 1u);
        }

        uint32_t geometry = 0u;
        uint32_t origin_x = state & 0xffffu;
        uint32_t origin_y = (state >> 16u) & 0xffffu;
        for (uint32_t point = 0; point < CK_GEOMETRY_POINTS; ++point) {
            uint32_t x = (left[point] >> 8u) & 0xffffu;
            uint32_t y = (right[point] >> 8u) & 0xffffu;
            uint32_t dx = x - origin_x;
            uint32_t dy = y - origin_y;
            geometry += dx * dx + dy * dy;
            geometry ^= rotate_left(dx * dy, point + 3u);
        }
        checksum ^= geometry;

        left[iteration & (CK_ARRAY_WORDS - 1u)] ^= state + checksum;
        source[iteration & (CK_COPY_BYTES - 1u)] ^= (uint8_t)checksum;
    }

    return checksum ^ state ^ mixed[seed & (CK_ARRAY_WORDS - 1u)];
}
