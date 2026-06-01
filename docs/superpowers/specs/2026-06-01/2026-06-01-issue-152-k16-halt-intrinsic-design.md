# K16 Halt Intrinsic Design

> Issue: [#152](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/152)

## Context

The BIOS no-bootable path previously reported `HALTED` through MMIO control status and then entered a hot `loop {}`. The host observed the status, but the K16 CPU kept executing until slice budget exhaustion, returning `Pause` instead of a real CPU-level `Halt`. That made a logically idle VM burn server tick time.

## Decision

K16 `halt` is a CPU execution primitive. MMIO control status remains diagnostic state, but it is not a substitute for stopping CPU execution.

The first implementation path is an intrinsic-backed halt:

- K16 LLVM recognizes calls to `__k16_halt_once`.
- The backend lowers that call to the real K16 `halt` instruction (`0x0001`).
- `k16-rt` exposes `halt_once()` as a thin wrapper around the backend intrinsic.
- `k16-rt` exposes `halt_forever() -> !` as `loop { halt_once(); }`.

This intentionally mirrors real machines: the CPU halt/wait operation is one instruction, while "forever" is a software loop that re-enters halt after future interrupts/events wake the CPU.

## Non-Goals

- Do not synthesize `__k16_halt_once` in `k16-ld`.
- Do not introduce a K16 ASM parser in this slice.
- Do not make `halt` permanently terminal. Future interrupts/events may resume execution after one halt.

## Follow-Up

K16 inline assembly support remains useful for OS/kernel work, but it should be implemented as a separate assembler/parser target feature. Runtime code should continue using wrappers such as `k16_rt::halt_once()` for stable architecture primitives.

## Verification

- LLVM lit test: `toolchains/Compukter-Kraft-llvm/llvm/test/CodeGen/K16/halt-intrinsic.ll`
- Rust runtime tests in `rust/guest/k16-rt`
- Firmware smoke/build path for bundled BIOS/boot/kernel
