# Rux Low ABI v1 Pre-Freeze Gap Review

## Status

Status: pre-freeze candidate review.

This document records the remaining ABI questions before freezing `RUXI` image format version `1`. The goal is to avoid accidental feature creep while still leaving enough surface for Rux and external compiler frontends.

## Decision Summary

| Question | Decision for v1 | Reason |
| --- | --- | --- |
| Add `Noop`? | Do not add. | Frontends can omit no-op code. Padding/alignment is not part of the image ABI. |
| Add standalone `Halt`? | Do not add. | Entry `Return*` is already the program halt ABI. A separate halt instruction would duplicate behavior. |
| Add `U32Add`, `U32Sub`, `U32Mul`? | Do not add. | Wrapping add/sub/mul produce the same stored 32-bit bit pattern as `I32Add/Sub/Mul`. |
| Add `U64Add`, `U64Sub`, `U64Mul`? | Do not add. | Wrapping add/sub/mul produce the same stored 64-bit bit pattern as `I64Add/Sub/Mul`. |
| Add `I32Ne`, `I32Le`, `I32Gt`, `I32Ge` and 64-bit equivalents? | Do not add. | Frontends can compose from existing comparisons and control flow. Add only after a measured need. |
| Add `BoolNot`, `BoolAnd`, `BoolOr`? | Do not add. | Boolean control flow can be lowered with `I32Eq`, `JumpIfFalse`, and canonical `0`/`1` values. |
| Add explicit `U8/U16` arithmetic? | Do not add. | `Load8` and `Load16` zero-extend into registers. Frontends can use 32-bit arithmetic and store low bits. |
| Add explicit sign-extension from `i8/i16`? | Defer. | Current language/runtime surface is unsigned byte/word oriented. Add in v2 if signed narrow integer source types need it. |
| Add heap/object ABI? | Defer. | v1 is a low-level image ABI with flat memory and scalar registers. |
| Freeze machine profile with MMIO? | Do not freeze yet. | Image ABI can freeze independently from device/MMIO maps. |

## Canonical Integer Policy

The v1 instruction set uses canonical opcodes for operations whose signed and unsigned results are identical at the stored bit level.

Examples:

- unsigned 32-bit addition lowers to `I32Add`;
- unsigned 32-bit subtraction lowers to `I32Sub`;
- unsigned 32-bit multiplication lowers to `I32Mul`;
- unsigned 64-bit addition lowers to `I64Add`;
- unsigned 64-bit subtraction lowers to `I64Sub`;
- unsigned 64-bit multiplication lowers to `I64Mul`;
- equality uses raw bit comparison, so `I32Eq` and `I64Eq` cover signed and unsigned equality;
- unsigned division, remainder, less-than, and right shift keep separate `U*` opcodes because signedness changes the result.

This keeps the v1 opcode table compact without making external frontends depend on host-language undefined behavior.
The machine-readable opcode table records these frontend-facing names as `canonical_unsigned_aliases`; they are aliases for documentation and builder APIs, not extra serialized opcodes.

## Program Termination

The entry function terminates the program by returning:

- `ReturnUnit` -> unit halt;
- `ReturnI32` -> i32 halt result;
- `ReturnI64` -> i64 halt result;
- `ReturnAddr` -> address halt result;
- `ReturnBool` -> bool halt result.

A separate `Halt` instruction is intentionally omitted from v1. If a later machine profile needs hardware-style trap/halt opcodes, that should be evaluated as a new ABI version or a machine-profile-specific extension.

## Frontend Guidance

External frontends should target the documented v1 opcode set directly and avoid reserving behavior for omitted opcodes. If a frontend needs an operation that is not present in v1, it should lower to existing primitives or raise a frontend diagnostic.

Recommended examples:

- `a != b`: lower as `I32Eq` plus boolean inversion in control flow or arithmetic;
- `a <= b`: lower as swapped greater-than logic using existing less-than and equality;
- unsigned `a + b`, `a - b`, `a * b`: lower as `I32Add/Sub/Mul` or `I64Add/Sub/Mul` for the appropriate width;
- narrow integer arithmetic: perform 32-bit arithmetic and store with `Store8` or `Store16`.

## Revisit Triggers

Reopen these decisions only if at least one of the following happens:

- a real external frontend cannot reasonably lower a common construct without excessive code size;
- conformance fixtures expose ambiguity in existing semantics;
- benchmark data shows a missing opcode has material runtime cost;
- a machine/device profile requires an instruction-level primitive that cannot be expressed through the existing ABI.
