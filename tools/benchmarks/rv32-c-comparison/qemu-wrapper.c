/*
 * The Compukter Kraft Developers
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include <stdint.h>

#include "kernel.h"

#define UART_THR (*(volatile uint8_t *)0x10000000u)
#define UART_LSR (*(volatile uint8_t *)0x10000005u)
#define UART_THR_EMPTY 0x20u
#define SIFIVE_TEST (*(volatile uint32_t *)0x00100000u)

extern const uint32_t ck_batch_value;

static void uart_putc(uint8_t value) {
    while ((UART_LSR & UART_THR_EMPTY) == 0u) {
    }
    UART_THR = value;
}

static void uart_text(const char *text) {
    while (*text != '\0') {
        uart_putc((uint8_t)*text++);
    }
}

static void uart_hex32(uint32_t value) {
    static const char digits[] = "0123456789abcdef";
    for (uint32_t shift = 28u;; shift -= 4u) {
        uart_putc((uint8_t)digits[(value >> shift) & 15u]);
        if (shift == 0u) {
            break;
        }
    }
}

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

    uart_text("CK_RESULT\t");
    uart_hex32(sink);
    uart_putc('\n');
    SIFIVE_TEST = 0x5555u;
    for (;;) {
    }
}
