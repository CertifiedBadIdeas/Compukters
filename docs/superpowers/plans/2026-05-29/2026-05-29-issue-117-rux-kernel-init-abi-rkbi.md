# Issue 117: RKBI boot info slice

## Goal

Implement the first concrete kernel/init ABI slice: the bootloader writes a small boot info block for the kernel, and the kernel consumes it before loading `/bin/init.ruxe`.

## Scope

- Bootloader writes `RKBI` at `0x3f00` before jumping to the kernel.
- Boot info v1 contains the root partition start LBA and loaded kernel RUXE size.
- Kernel reads `RKBI` instead of rediscovering `ROOT` through `RUXPT`.
- Missing or invalid boot info fails explicitly.
- Keep init execution unchanged: kernel validates and jumps to `/bin/init.ruxe`.

## Test Plan

1. Add source-structure tests for bootloader `RKBI` write.
2. Add source-structure tests for kernel `RKBI` read.
3. Run the existing boot/kernel/init storage integration test.
4. Run the focused compiler crate tests.
