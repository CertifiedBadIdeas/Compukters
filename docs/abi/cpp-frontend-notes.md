# C++ Frontend Notes For Rux Low ABI v1

## Status

Status: advisory notes for the pre-freeze `RUXI` v1 ABI.

This document is not a separate ABI. It explains how a C++ frontend can target `docs/abi/rux-low-image-v1.md`.

## Integer Operations

The VM defines arithmetic behavior explicitly. Do not inherit C++ undefined behavior into generated images.

Recommended lowering:

- C++ signed `int32_t` add/sub/mul -> `I32Add` / `I32Sub` / `I32Mul`;
- C++ unsigned `uint32_t` add/sub/mul -> canonical `I32Add` / `I32Sub` / `I32Mul`;
- C++ signed `int64_t` add/sub/mul -> `I64Add` / `I64Sub` / `I64Mul`;
- C++ unsigned `uint64_t` add/sub/mul -> canonical `I64Add` / `I64Sub` / `I64Mul`;
- signed division/remainder -> `I32Div` / `I32Rem` / `I64Div` / `I64Rem`;
- unsigned division/remainder -> `U32Div` / `U32Rem` / `U64Div` / `U64Rem`;
- signed less-than -> `I32Lt` / `I64Lt`;
- unsigned less-than -> `U32Lt` / `U64Lt`;
- equality -> `I32Eq` / `I64Eq`;
- signed right shift -> `I32Shr` / `I64Shr`;
- unsigned right shift -> `U32Shr` / `U64Shr`.

Shift counts are unbounded in the VM. Counts outside the value width produce the ABI-defined result instead of using C++ or CPU masking behavior.

The names `U32Add`, `U32Sub`, `U32Mul`, `U64Add`, `U64Sub`, and `U64Mul` are canonical unsigned aliases documented in the opcode metadata. They are not serialized opcodes. A frontend may use those names internally, but it must emit the signed-named canonical opcodes listed above.

## Overflow

`I32Add`, `I32Sub`, `I32Mul`, `I64Add`, `I64Sub`, and `I64Mul` are wrapping VM operations. A C++ frontend may lower wrapping-capable source operations directly to them.

If the source language wants C++ signed-overflow UB or diagnostics, enforce that in the frontend before emitting the image. The VM itself does not encode UB.

## Pointers And Memory

Rux low addresses are 32-bit byte addresses.

Recommended lowering:

- pointer constants -> `AddrConst`;
- pointer addition -> `AddrAdd`;
- `uint8_t` load/store -> `Load8` / `Store8`;
- `uint16_t` load/store -> `Load16` / `Store16`;
- `uint32_t` / `int32_t` load/store -> `Load32` / `Store32`;
- `uint64_t` / `int64_t` load/store -> `Load64` / `Store64`.

The VM does not require alignment. A C++ frontend may add its own alignment rules if the source language profile requires them.

## Bool

Comparison opcodes write `0` or `1`. `JumpIfFalse` treats low 32 bits equal to `0` as false and any non-zero value as true.

For stable interop, emit canonical bool values `0` or `1` when materializing booleans manually.

## Calls

`CallStatic` copies raw 64-bit register slots into callee parameter registers by position. There are no caller-saved or callee-saved registers because every function has its own frame-local register window.

A frontend must choose `return_register` according to the callee's return kind:

- scalar return -> `Some(reg)`;
- unit return -> `None`.

The ABI has no source-level function type metadata, so a frontend should validate call signatures before image emission.

## Not Yet Stable

These areas are not part of frozen v1 yet:

- heap layout;
- stack layout convention for source-language locals beyond compiler-chosen registers and explicit memory;
- C++ object model;
- exception handling;
- virtual dispatch;
- stable MMIO map.

Do not rely on experimental machine/device behavior unless it is later promoted into a versioned ABI or machine profile.
