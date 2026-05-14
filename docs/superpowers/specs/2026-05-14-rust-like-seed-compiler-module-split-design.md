# Rust-Like Seed Compiler Module Split Design

## Goal

Split the Rust-like seed compiler out of one large `lib.rs` into small modules without changing compiler behavior or public API.

## Motivation

`native/ckl-compiler/src/lib.rs` has grown past 1500 lines while the language is still small. Keeping lexer, AST, parser, const evaluation, and codegen in one file makes every next language feature harder to review and easier to break.

This is a pure refactor. The compiler should emit exactly the same low images and diagnostics after the split.

## Target Layout

```text
native/ckl-compiler/src/
  lib.rs
  error.rs
  lexer.rs
  ast.rs
  parser.rs
  codegen.rs
```

## Module Responsibilities

`error.rs`:

- `CompileError`;
- `Display`;
- `std::error::Error`.

`lexer.rs`:

- `Token`;
- `TokenKind`;
- `lex`;
- token display helpers.

`ast.rs`:

- source AST nodes;
- source operators;
- return type.

`parser.rs`:

- recursive-descent parser;
- public crate-private `parse(tokens)` entry point.

`codegen.rs`:

- built-in ABI constants;
- source const evaluation;
- function signature validation;
- low image lowering.

`lib.rs`:

- module declarations;
- public re-exports for existing API;
- public `compile(source)` facade.

## Public API Compatibility

The following API remains public:

```rust
pub use error::CompileError;
pub use lexer::{lex, Token, TokenKind};

pub fn compile(source: &str) -> Result<Image, CompileError>;
```

AST, parser, and codegen remain crate-private.

## Testing Strategy

No new behavior tests are required. Existing compiler and low VM tests act as characterization tests:

- `cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml`;
- `cargo fmt --manifest-path native/ckl-compiler/Cargo.toml --check`;
- `cargo test --offline --manifest-path native/ckl-vm/Cargo.toml`.

## Rollout

1. Extract `error.rs` and `lexer.rs`.
2. Extract `ast.rs` and `parser.rs`.
3. Extract `codegen.rs`.
4. Run full verification.

Each extraction should compile and be committed separately.

## Non-Goals

- Do not add language features.
- Do not change diagnostics intentionally.
- Do not change low VM image output intentionally.
- Do not split integration tests in this slice.
- Do not introduce a CLI.

## Success Criteria

- `lib.rs` becomes a small facade.
- Compiler tests keep passing unchanged.
- Low VM tests keep passing unchanged.
- Worktree is clean after commits.
