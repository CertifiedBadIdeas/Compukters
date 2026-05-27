# Rust-Like Seed Bitwise And Comments Design

## Goal

Add the next small low-level language slice to the Rust-like seed compiler: line comments and `i32` bitwise operators.

## Motivation

The seed language is aimed at bare-metal VM programs, so bit manipulation must be first-class. ABI status words, MMIO flags, masks, shifts, packed fields, and future device registers should be writable without falling back to hand-built low-image instruction lists.

Line comments are included in the same slice because they are tiny, immediately useful for bare-metal samples, and do not affect the AST or VM.

## Scope

The slice adds:

- `// line comments` in the lexer.
- `&`, `|`, `^`, `<<`, and `>>` tokens.
- `i32` binary expressions for bitwise and shift operations.
- Low-image `I32BitAnd` and `I32BitOr` instructions.
- Compiler lowering from seed expressions to low-image instructions.
- Constant evaluation for bitwise expressions in top-level `const` initializers.

The slice does not add:

- boolean `&&` / `||`;
- unary bitwise not;
- typed integer widths beyond `i32`;
- assignment operators such as `&=`;
- a new ABI version migration path.

## Parsing

The parser keeps Rust/C-like precedence for the supported subset:

```text
comparison:  < <= > >= == !=
bitwise or:  |
bitwise xor: ^
bitwise and: &
shift:       << >>
add/sub:     + -
mul/div:     * /
postfix:     call, method call
primary:     literals, locals, mmio, ptr, grouped expressions
```

Comparisons remain non-chainable. `a < b < c` is still rejected by the existing grammar shape.

## Low-Image ABI

Existing low-image tags are kept stable. The new instructions use additive tags:

- `26`: `I32BitAnd { dst, lhs, rhs }`
- `27`: `I32BitOr { dst, lhs, rhs }`

The low-image version stays unchanged because this experimental branch already owns the compiler and runner together, and the extension is additive for current tests and generated images. No fallback decoder path is introduced.

## Runtime Semantics

All operators work on `i32` registers:

- `&`, `|`, `^` use Rust integer bitwise semantics.
- `<<` uses the runner's existing `wrapping_shl(rhs as u32)` semantics.
- `>>` uses the runner's existing `wrapping_shr(rhs as u32)` semantics.

The compiler emits normal low-image instructions. The runner may fuse immediate right operands in its existing pre-execution lowering path.

## Testing

Tests should cover:

- Lexer output for comments and bitwise tokens.
- Low VM execution of `I32BitAnd` and `I32BitOr`.
- Compiler instruction lowering for all five operators.
- End-to-end execution of a seed program using masks and shifts.
- Constant evaluation for bitwise const initializers.

## Self-Review

- Scope is limited to bitwise/comment support and does not add unrelated language features.
- Existing instruction tags are not renumbered.
- No Kotlin fallback or old VM path is introduced.
- Shift semantics are explicitly tied to the current low VM runner.
