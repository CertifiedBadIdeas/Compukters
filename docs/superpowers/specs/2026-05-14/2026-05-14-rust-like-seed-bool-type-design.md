# Rust-Like Seed Bool Type Design

## Goal

Make `bool` a real seed-language type and add `true` / `false` literals.

## Motivation

The low VM already has boolean register semantics through comparison instructions, `JumpIfFalse`, and `ReturnBool`. The seed compiler currently models comparison results as `i32`, which lets programs such as `if 1 { ... }` compile even though the language direction is Rust-like.

Before adding more low-level features, the compiler should stop mixing integer and boolean values.

## Scope

This slice adds:

- `bool`, `true`, and `false` tokens.
- `bool` parameter, local, and return annotations.
- `ExprValue::Bool` and `ValueType::Bool` in codegen.
- Comparisons that produce `bool`.
- `if` and `while` conditions that require `bool`.
- `return` lowering to `ReturnBool` for `fn ... -> bool`.
- Type checks for assignments and function arguments.

This slice does not add:

- `!`, `&&`, or `||`;
- boolean top-level `const`;
- implicit integer-to-bool or bool-to-integer conversion;
- new low-image instructions.

## Lowering

Boolean literals lower to ordinary raw register values:

- `true` emits `I32Const { value: 1 }` and is typed as `Bool`.
- `false` emits `I32Const { value: 0 }` and is typed as `Bool`.

This reuses the low VM's existing `read_bool(register) != 0` behavior and avoids adding a `BoolConst` instruction before it is needed.

Comparisons continue to emit the existing low-image comparison instructions:

- `<` emits `I32Lt`.
- `==` emits `I32Eq`.
- `!=`, `<=`, `>`, and `>=` keep using small instruction sequences.

The compiler now records those destination registers as `Bool`.

## Type Rules

- Arithmetic and bitwise operators require `i32` operands and produce `i32`.
- Comparisons require `i32` operands and produce `bool`.
- `if` and `while` require `bool` conditions.
- `fn main() -> bool` returns through `ReturnBool`.
- `bool` locals and function parameters are allowed.
- Assignment requires the assigned expression type to match the local type.
- Function calls require argument expression types to match parameter types.

## Testing

Tests should cover:

- lexer support for `bool`, `true`, and `false`;
- bool local declaration and `return bool`;
- bool comparisons in `if` / `while`;
- rejection of `if 1`;
- rejection of assigning bool to i32 and i32 to bool;
- rejection of wrong function argument types;
- end-to-end execution returning `HaltBool`.

## Self-Review

- The design does not add boolean operators; that is the next slice.
- No new VM instruction is required.
- No implicit conversions are introduced.
- Existing low-image ABI remains unchanged.
