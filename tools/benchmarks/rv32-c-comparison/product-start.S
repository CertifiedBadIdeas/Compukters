/*
 * The Compukter Kraft Developers
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

    .option norvc
    .section .text.start, "ax", @progbits
    .globl _start
    .type _start, @function
_start:
    .option push
    .option norelax
    la sp, __stack_top
    .option pop
    call platform_main
1:
    j 1b
    .size _start, . - _start
