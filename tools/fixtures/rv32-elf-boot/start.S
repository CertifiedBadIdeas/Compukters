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

    .option norvc
    .section .text.start, "ax", @progbits
    .globl _start
    .type _start, @function
_start:
    .option push
    .option norelax
    la sp, __stack_top
    .option pop
    call boot_main
1:
    j 1b
    .size _start, . - _start
