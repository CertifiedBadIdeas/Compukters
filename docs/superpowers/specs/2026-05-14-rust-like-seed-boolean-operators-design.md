# Rust-Like Seed Boolean Operators Design

## Goal

Add Rust-like boolean operators to the seed compiler:

- unary `!`;
- short-circuit `&&`;
- short-circuit `||`.

## Motivation

The seed language now has a real `bool` type, but boolean composition still requires nested `if` blocks or helper functions. Bare-metal code needs compact guard expressions for status checks and MMIO conditions.

Because the language is Rust-like, `&&` and `||` must short-circuit. Lowering them as bitwise operations would be faster to implement, but it would execute side-effecting right-hand expressions and create a semantic trap.

## Scope

This slice adds:

- tokens for `!`, `&&`, and `||`;
- AST nodes for unary boolean not and logical boolean operations;
- parser precedence where `||` is lowest, then `&&`, then comparison/bitwise/arithmetic;
- codegen for `!` using existing comparison instructions;
- codegen for `&&` and `||` using low-image jumps;
- tests proving right-hand short-circuit side effects do not run.

This slice does not add:

- bitwise not `~`;
- `&=` / `|=` assignment operators;
- boolean top-level consts;
- new low-image instructions.

## Lowering

`!value` lowers to a bool comparison against `false`:

```text
zero = false
dst = value == zero
```

`lhs && rhs` lowers with short-circuit:

```text
dst = false
if !lhs jump end
rhs_value = rhs
dst = rhs_value
end:
```

`lhs || rhs` lowers with short-circuit:

```text
dst = true
if lhs jump end
rhs_value = rhs
dst = rhs_value
end:
```

The actual low-image instruction set only has `JumpIfFalse`, so `||` uses a small inverted jump sequence.

## Type Rules

- `!` requires `bool` and produces `bool`.
- `&&` requires two `bool` operands and produces `bool`.
- `||` requires two `bool` operands and produces `bool`.
- No implicit integer-to-bool conversion is introduced.

## Testing

Tests should cover:

- lexer output for `!`, `&&`, and `||`;
- lowering contains jumps for logical expressions;
- end-to-end `!`, `&&`, and `||` results;
- `&&` skips a right-hand function with MMIO side effects when the left side is `false`;
- `||` skips a right-hand function with MMIO side effects when the left side is `true`;
- type errors for `!1`, `true && 1`, and `1 || false`.

## Self-Review

- The design preserves Rust-like short-circuit semantics.
- The design does not add VM opcodes.
- The design keeps all operands typed as `bool`.
