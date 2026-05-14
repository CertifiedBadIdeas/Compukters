# Low VM U32 Comparisons Design

## Goal

Make `u32` ordering comparisons correct in the Rust-like seed language by adding a low VM unsigned less-than instruction.

## Architecture

Low VM registers remain raw 32-bit word slots for `i32` and `u32` values. Signedness is determined by the operation:

- `I32Lt` interprets operands as signed `i32`;
- `U32Lt` interprets operands as unsigned `u32`;
- `I32Eq` remains valid for both `i32` and `u32`, because equality is bitwise.

## Instruction

Add:

```rust
Instruction::U32Lt { dst, lhs, rhs }
```

Execution reads `lhs` and `rhs` as 32-bit words and compares them as `u32`:

```rust
(read_i32(lhs) as u32) < (read_i32(rhs) as u32)
```

The result is written as a bool register.

## Compiler Lowering

For `u32` operands:

- `<` lowers to `U32Lt(lhs, rhs)`;
- `>` lowers to `U32Lt(rhs, lhs)`;
- `<=` lowers to `!(rhs < lhs)`;
- `>=` lowers to `!(lhs < rhs)`;
- `==` lowers to `I32Eq`;
- `!=` lowers to `I32Eq` plus bool inversion.

Mixed `i32` / `u32` comparisons are compile errors unless the source uses an explicit cast.

## Non-Goals

- Do not add `U32Div` in this slice.
- Do not add separate unsigned register storage.
- Do not add `U32Le`, `U32Gt`, or `U32Ge` until profiling or readability requires them.
- Do not change `Load32` / `Store32`.

## Tests

Coverage should include:

- low VM runner executes `U32Lt` differently from signed comparison;
- low image decoder recognizes the new instruction tag;
- compiler lowers `u32 < u32` to `U32Lt`;
- `u32 >`, `<=`, and `>=` execute correctly for high-bit values;
- mixed `i32` / `u32` comparisons are rejected.
