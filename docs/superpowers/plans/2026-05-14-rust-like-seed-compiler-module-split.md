# Rust-Like Seed Compiler Module Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Split `native/rux-compiler/src/lib.rs` into focused compiler modules without changing behavior.

**Architecture:** Keep `lib.rs` as the public facade, move lexer/error, AST/parser, and codegen into separate crate-private modules. Use the existing compiler and VM tests as characterization tests for this refactor.

**Tech Stack:** Rust 2021, Cargo, `rux-vm::low_image`, existing `native/rux-compiler` tests.

---

## File Structure

- Create: `native/rux-compiler/src/error.rs`
- Create: `native/rux-compiler/src/lexer.rs`
- Create: `native/rux-compiler/src/ast.rs`
- Create: `native/rux-compiler/src/parser.rs`
- Create: `native/rux-compiler/src/codegen.rs`
- Modify: `native/rux-compiler/src/lib.rs`
- Existing tests: `native/rux-compiler/tests/compiler_seed.rs`

## Task 1: Extract Error And Lexer

**Files:**
- Create: `native/rux-compiler/src/error.rs`
- Create: `native/rux-compiler/src/lexer.rs`
- Modify: `native/rux-compiler/src/lib.rs`

- [x] **Step 1: Move `CompileError` into `error.rs`**

Move:

```rust
use std::fmt::{Display, Formatter};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CompileError {
    pub message: String,
}

impl Display for CompileError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.message)
    }
}

impl std::error::Error for CompileError {}
```

- [x] **Step 2: Move lexer into `lexer.rs`**

Move:

- `Token`;
- `TokenKind`;
- `lex`;
- `impl TokenKind { fn name(&self) -> &'static str }`.

Add `use crate::error::CompileError;` at the top of `lexer.rs`.

- [x] **Step 3: Update `lib.rs` facade**

Keep:

```rust
mod ast;
mod codegen;
mod error;
mod lexer;
mod parser;

pub use error::CompileError;
pub use lexer::{lex, Token, TokenKind};

pub fn compile(source: &str) -> Result<Image, CompileError> {
    let tokens = lex(source)?;
    let program = parser::parse(tokens)?;
    codegen::compile(program)
}
```

Temporarily leave AST/parser/codegen definitions in `lib.rs` if they have not been extracted yet.

- [x] **Step 4: Verify and commit**

Run:

```bash
cargo test --offline --manifest-path native/rux-compiler/Cargo.toml
cargo fmt --manifest-path native/rux-compiler/Cargo.toml --check
git add native/rux-compiler/src/lib.rs native/rux-compiler/src/error.rs native/rux-compiler/src/lexer.rs
git commit -m "Split rust language seed compiler lexer"
```

## Task 2: Extract AST And Parser

**Files:**
- Create: `native/rux-compiler/src/ast.rs`
- Create: `native/rux-compiler/src/parser.rs`
- Modify: `native/rux-compiler/src/lib.rs`
- Modify: `native/rux-compiler/src/codegen.rs` if needed after Task 3

- [x] **Step 1: Move AST into `ast.rs`**

Move:

- `Program`;
- `ConstDecl`;
- `FunctionDecl`;
- `Parameter`;
- `ReturnType`;
- `Statement`;
- `Expr`;
- `BinaryOp`;
- `CompareOp`.

Mark fields and variants `pub(crate)` where required by parser/codegen.

- [x] **Step 2: Move parser into `parser.rs`**

Move:

- `Parser`;
- all `impl Parser` methods.

Add:

```rust
pub(crate) fn parse(tokens: Vec<Token>) -> Result<Program, CompileError> {
    Parser::new(tokens).parse_program()
}
```

Use imports:

```rust
use crate::ast::*;
use crate::error::CompileError;
use crate::lexer::{Token, TokenKind};
```

- [x] **Step 3: Update `lib.rs` compile facade**

Use:

```rust
pub fn compile(source: &str) -> Result<Image, CompileError> {
    let tokens = lex(source)?;
    let program = parser::parse(tokens)?;
    codegen::compile(program)
}
```

- [x] **Step 4: Verify and commit**

Run:

```bash
cargo test --offline --manifest-path native/rux-compiler/Cargo.toml
cargo fmt --manifest-path native/rux-compiler/Cargo.toml --check
git add native/rux-compiler/src/lib.rs native/rux-compiler/src/ast.rs native/rux-compiler/src/parser.rs
git commit -m "Split rust language seed compiler parser"
```

## Task 3: Extract Codegen

**Files:**
- Create: `native/rux-compiler/src/codegen.rs`
- Modify: `native/rux-compiler/src/lib.rs`

- [x] **Step 1: Move codegen into `codegen.rs`**

Move:

- `ExprValue`;
- `PointerKind`;
- `AddressContext`;
- `BuiltinConstant`;
- `resolve_builtin_constant`;
- `evaluate_consts`;
- `evaluate_const_expr`;
- `ValueType`;
- `Local`;
- `FunctionSignature`;
- `collect_function_signatures`;
- `BlockOutcome`;
- `Codegen`;
- `impl Codegen`.

Add:

```rust
pub(crate) fn compile(program: Program) -> Result<Image, CompileError> {
    Codegen::compile(program)
}
```

Use imports:

```rust
use crate::ast::*;
use crate::error::CompileError;
use rux_vm::computer_abi;
use rux_vm::low_image::{Function, Image, Instruction};
use std::collections::{HashMap, HashSet};
```

- [x] **Step 2: Reduce `lib.rs` to facade**

`lib.rs` should contain only module declarations, public re-exports, and `compile`.

- [x] **Step 3: Verify and commit**

Run:

```bash
cargo test --offline --manifest-path native/rux-compiler/Cargo.toml
cargo fmt --manifest-path native/rux-compiler/Cargo.toml --check
git add native/rux-compiler/src/lib.rs native/rux-compiler/src/codegen.rs
git commit -m "Split rust language seed compiler codegen"
```

## Task 4: Final Verification And Docs

**Files:**
- Modify: `docs/superpowers/specs/2026-05-14-rust-like-seed-compiler-module-split-design.md`
- Modify: `docs/superpowers/plans/2026-05-14-rust-like-seed-compiler-module-split.md`

- [x] **Step 1: Append implementation status**

Append to the design:

```markdown
## Implementation Status

Implemented in `native/rux-compiler`:

- `error.rs` for compiler errors;
- `lexer.rs` for tokenization;
- `ast.rs` for source AST;
- `parser.rs` for recursive-descent parsing;
- `codegen.rs` for low image lowering;
- `lib.rs` as a small public facade.
```

- [x] **Step 2: Mark plan complete**

Change all task checkboxes from `[ ]` to `[x]`.

- [x] **Step 3: Final verification and commit**

Run:

```bash
cargo test --offline --manifest-path native/rux-compiler/Cargo.toml
cargo fmt --manifest-path native/rux-compiler/Cargo.toml --check
cargo test --offline --manifest-path native/rux-vm/Cargo.toml
git add docs/superpowers/specs/2026-05-14-rust-like-seed-compiler-module-split-design.md docs/superpowers/plans/2026-05-14-rust-like-seed-compiler-module-split.md
git commit -m "Document rust language seed compiler module split"
```

## Final Verification

Run:

```bash
cargo test --offline --manifest-path native/rux-compiler/Cargo.toml
cargo fmt --manifest-path native/rux-compiler/Cargo.toml --check
cargo test --offline --manifest-path native/rux-vm/Cargo.toml
git status --short
```

Expected:

- compiler tests pass;
- formatting check passes;
- low VM tests pass;
- git status is clean.
