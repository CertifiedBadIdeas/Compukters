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
    la t0, trap_handler
    csrw mtvec, t0
    la t0, __trap_stack_top
    csrw mscratch, t0
    .option pop
    call trap_main
1:
    j 1b
    .size _start, . - _start

    .section .text.trap, "ax", @progbits
    .align 2
    .type trap_handler, @function
trap_handler:
    csrrw sp, mscratch, sp
    addi sp, sp, -16
    sw t0, 0(sp)
    sw t1, 4(sp)
    sw t2, 8(sp)
    csrr t0, mcause
    li t1, 11
    bne t0, t1, trap_panic
    csrr t0, mtval
    bnez t0, trap_panic
    csrr t0, mepc
    addi t0, t0, 4
    csrw mepc, t0
    lw t0, 0(sp)
    lw t1, 4(sp)
    lw t2, 8(sp)
    addi sp, sp, 16
    csrrw sp, mscratch, sp
    mret
    .size trap_handler, . - trap_handler

trap_panic:
    li t0, 1
    lui t1, 0x10000
    sw t0, 4(t1)
    li t0, 4
    sw t0, 0(t1)
1:
    j 1b
