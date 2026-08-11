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
#define CONTROL_PANIC_CODE (*(volatile u32 *)0x10000004u)
#define CONTROL_EXIT_CODE (*(volatile u32 *)0x10000008u)
#define DEBUG_WRITE (*(volatile unsigned char *)0x10000100u)

static u32 atomic_word = 41u;
static const volatile unsigned char marker[] = "RV32 ELF ATOMIC OK\n";

static __attribute__((noreturn)) void panic(u32 code) {
    CONTROL_PANIC_CODE = code;
    CONTROL_STATUS = 4u;
    for (;;) {
    }
}

__attribute__((noreturn)) void atomic_main(void) {
    u32 old = __atomic_fetch_add(&atomic_word, 1u, __ATOMIC_ACQ_REL);
    if (old != 41u) {
        panic(1u);
    }
    if (__atomic_load_n(&atomic_word, __ATOMIC_ACQUIRE) != 42u) {
        panic(2u);
    }

    __asm__ volatile("fence iorw, iorw" ::: "memory");
    __asm__ volatile("fence.i" ::: "memory");

    for (u32 index = 0; index + 1u < sizeof(marker); ++index) {
        DEBUG_WRITE = (unsigned char)marker[index];
    }
    CONTROL_EXIT_CODE = 0u;
    CONTROL_STATUS = 3u;
    for (;;) {
    }
}
