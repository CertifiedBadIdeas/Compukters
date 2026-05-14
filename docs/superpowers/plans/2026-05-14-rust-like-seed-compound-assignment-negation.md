# Rust-Like Seed Compound Assignment And Negation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add compound assignment operators and unary `i32` negation to the Rust-like seed compiler.

**Architecture:** Extend lexer/parser/AST with assignment operators and unary `Neg`. Codegen lowers compound assignment directly into existing `I32*` instructions using the local register as destination, and lowers unary minus as `0 - expr`.

**Tech Stack:** Rust compiler crate `native/ckl-compiler`, low VM image instructions from `native/ckl-vm`, Cargo tests run offline.

---

### Task 1: Add Failing Tests

**Files:**
- Modify: `native/ckl-compiler/tests/compiler_seed.rs`

- [ ] **Step 1: Add tests**

Add tests for:

```rust
lexer_recognizes_compound_assignment_tokens
compile_lowers_i32_compound_assignment
compile_lowers_i32_unary_minus
compile_lowers_u32_bitwise_compound_assignment
compile_rejects_bool_compound_assignment
compile_rejects_u32_unary_minus
compiled_seed_compound_assignment_and_negation_run_on_computer_machine
compiled_seed_u32_compound_assignment_runs_on_computer_machine
```

- [ ] **Step 2: Verify RED**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml compound
```

Expected: failures because tokens and lowering do not exist yet.

### Task 2: Implement Lexer, Parser, And AST

**Files:**
- Modify: `native/ckl-compiler/src/lexer.rs`
- Modify: `native/ckl-compiler/src/parser.rs`
- Modify: `native/ckl-compiler/src/ast.rs`

- [ ] **Step 1: Add compound assignment tokens**

Add `+=`, `-=`, `*=`, `/=`, `&=`, `|=`, `^=`, `<<=`, and `>>=`.

- [ ] **Step 2: Add AST nodes**

Add a compound assignment statement and unary negation:

```rust
Statement::AssignOp { name, op, value }
UnaryOp::Neg
```

- [ ] **Step 3: Parse the new forms**

Parse compound assignment before expression statements. Parse unary `-` at the same precedence level as unary `!`.

### Task 3: Implement Codegen

**Files:**
- Modify: `native/ckl-compiler/src/codegen.rs`

- [ ] **Step 1: Lower compound assignment**

Resolve the target local, enforce mutability, enforce `i32`/`u32`, compile RHS as the local type, then emit the matching binary instruction into the local register.

- [ ] **Step 2: Lower unary minus**

Compile the operand as `i32`, emit zero, then emit `I32Sub`.

### Task 4: Verify And Commit

**Files:**
- All changed files

- [ ] **Step 1: Format check**

Run:

```bash
cargo fmt --manifest-path native/ckl-compiler/Cargo.toml --check
```

Expected: exit code 0.

- [ ] **Step 2: Full compiler tests**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml
```

Expected: all compiler tests pass.

- [ ] **Step 3: VM regression tests**

Run:

```bash
cargo test --offline --manifest-path native/ckl-vm/Cargo.toml
```

Expected: all VM tests pass.

- [ ] **Step 4: Commit**

Run:

```bash
git add docs/superpowers/specs/2026-05-14-rust-like-seed-compound-assignment-negation-design.md docs/superpowers/plans/2026-05-14-rust-like-seed-compound-assignment-negation.md native/ckl-compiler/src/ast.rs native/ckl-compiler/src/lexer.rs native/ckl-compiler/src/parser.rs native/ckl-compiler/src/codegen.rs native/ckl-compiler/tests/compiler_seed.rs
git commit -m "feat: add seed compound assignment"
```
