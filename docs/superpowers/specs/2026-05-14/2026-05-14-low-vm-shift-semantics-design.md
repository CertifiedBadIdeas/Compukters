# Low VM Shift Semantics Design

## Goal

Make low VM shift behavior explicit and predictable for the Rust-like seed language.

## Problem

The low VM currently executes shifts with Rust `wrapping_shl` and `wrapping_shr`. Those methods mask the right-hand side shift count. For 32-bit values this means:

```text
42 << 32 == 42
5 << 1025 == 10
```

That matches many CPU shift instructions, but it is surprising for source-language users. It also breaks `u32 >>` semantics because current `u32` values are stored in raw 32-bit registers and lowered through `I32Shr`, which performs signed arithmetic right shift.

## Decision

Use explicit unbounded 32-bit shift semantics:

- `i32 << rhs`: if `rhs >= 32` or `rhs < 0`, result is `0`; otherwise shift left.
- `u32 << rhs`: if `rhs >= 32` or `rhs < 0`, result is `0`; otherwise shift left.
- `i32 >> rhs`: arithmetic right shift; if `rhs >= 32` or `rhs < 0`, result is `-1` for negative lhs and `0` for non-negative lhs.
- `u32 >> rhs`: logical right shift; if `rhs >= 32` or `rhs < 0`, result is `0`.

Negative shift counts are treated as out of range because the low VM stores the shift count in an `i32` register. This avoids reintroducing masked CPU behavior through `as u32`.

## ISA Changes

Add two low image instructions:

```text
U32Shl dst, lhs, rhs
U32Shr dst, lhs, rhs
```

`I32Shl` and `I32Shr` remain signed-source operations. `U32Shl` and `U32Shr` are used by the compiler when both operands are `u32`.

Instruction tags:

- `29`: `U32Shl`
- `30`: `U32Shr`

The experimental branch can extend version `5` without preserving legacy low images beyond the existing decoder tests.

## Compiler Lowering

The Rust-like seed compiler already compiles `u32` binary expressions separately from `i32` binary expressions. Change `u32` lowering for shifts:

- `u32 << u32` -> `U32Shl`
- `u32 >> u32` -> `U32Shr`

Other `u32` arithmetic and bitwise operations can continue to use the existing raw-word `I32*` instructions where signedness does not affect bit-level results.

## Tests

Add low VM tests proving:

- `I32Shl(42, 32)` returns `0`;
- `I32Shr(-1, 32)` returns `-1`;
- `U32Shr(0x80000000, 1)` returns `0x40000000`;
- `U32Shr(0x80000000, 32)` returns `0`.

Add compiler tests proving:

- `0x80000000u32 >> 1u32` returns `0x40000000` as an `i32` bit pattern;
- `42 << 32` returns `0`;
- `0x80000000u32 >> 32u32` returns `0`;
- `u32` shift lowering emits `U32Shl` / `U32Shr`.

## Non-Goals

- Do not add rotate instructions.
- Do not add trapping shifts.
- Do not change division or arithmetic overflow behavior.
- Do not add wider integer types in this slice.
