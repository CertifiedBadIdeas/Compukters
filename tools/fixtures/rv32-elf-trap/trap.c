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

typedef unsigned int u32;

#define CONTROL_STATUS (*(volatile u32 *)0x10000000u)
#define CONTROL_EXIT_CODE (*(volatile u32 *)0x10000008u)
#define DEBUG_WRITE (*(volatile unsigned char *)0x10000100u)

static const volatile unsigned char marker[] = "RV32 TRAP OK\n";

__attribute__((noreturn)) void trap_main(void) {
    __asm__ volatile("ecall" ::: "memory");
    for (u32 index = 0; index + 1u < sizeof(marker); ++index) {
        DEBUG_WRITE = (unsigned char)marker[index];
    }
    CONTROL_EXIT_CODE = 0u;
    CONTROL_STATUS = 3u;
    for (;;) {
    }
}
