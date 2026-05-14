# Rust-Like Seed Compound Assignment And Negation Design

## Goal

Add small Rust-like expression ergonomics to the seed compiler:

- compound assignment for mutable numeric locals;
- unary `-` for `i32`.

## Scope

This is a compiler-only change. It uses existing low VM arithmetic and bitwise instructions and does not change the low image ABI or runner.

## Compound Assignment

Supported forms:

```rust
value += 1;
value -= 1;
value *= 2;
value /= 2;
value &= 0xff;
value |= 0x10;
value ^= 0x20;
value <<= 1;
value >>= 1;
```

The target must be a mutable local of type `i32` or `u32`. The right-hand expression must match the target type, using the same strict typing rules as normal assignment. `bool` compound assignment is rejected.

Lowering emits the existing low instruction with the local register as both `dst` and `lhs`, for example:

```text
x += rhs  ->  I32Add { dst: x, lhs: x, rhs }
```

For `u32`, this remains a typed compiler view over the existing 32-bit low VM operations.

## Unary Minus

Supported form:

```rust
let mut x: i32 = -1;
x = -x;
```

Unary minus is only valid for `i32`. It lowers as `0 - expr` using `I32Sub`. Applying unary minus to `u32` or `bool` is a compile error.

## Tests

Coverage should include:

- lexer tokens for compound assignments;
- `i32` compound assignment lowering/execution;
- `u32` bitwise compound assignment lowering/execution;
- unary negative literals and variables;
- errors for compound assignment on `bool`;
- errors for unary minus on `u32`.
