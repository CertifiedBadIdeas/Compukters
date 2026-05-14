# Rust-Like Seed Loop Control Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `break;` and `continue;` to the Rust-like seed compiler by lowering them to existing low VM jumps.

**Architecture:** Lexer and parser add keyword-backed statement forms. Codegen maintains a loop-context stack and patches `break` exits after each `while` body. Block outcome tracking distinguishes returns from other terminating loop-control statements.

**Tech Stack:** Rust compiler crate `native/ckl-compiler`, low VM image instructions from `native/ckl-vm`, Cargo tests run offline.

---

### Task 1: Add Loop Control Tests

**Files:**
- Modify: `native/ckl-compiler/tests/compiler_seed.rs`

- [ ] **Step 1: Add failing lexer, lowering, execution, and error tests**

Add tests for:

```rust
lexer_recognizes_loop_control_keywords
compile_lowers_break_and_continue_in_while
compile_rejects_break_outside_loop
compile_rejects_continue_outside_loop
compile_rejects_unreachable_statement_after_break
compile_rejects_unreachable_statement_after_continue
compiled_seed_break_continue_program_runs_on_computer_machine
```

- [ ] **Step 2: Run compiler tests and verify RED**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml loop_control
```

Expected: failure because `TokenKind::Break`, `TokenKind::Continue`, and parser/codegen support do not exist yet.

### Task 2: Add Lexer And Parser Support

**Files:**
- Modify: `native/ckl-compiler/src/lexer.rs`
- Modify: `native/ckl-compiler/src/ast.rs`
- Modify: `native/ckl-compiler/src/parser.rs`

- [ ] **Step 1: Add tokens and AST variants**

Add `Break` and `Continue` token kinds, keyword matching, token names, and `Statement::Break` / `Statement::Continue`.

- [ ] **Step 2: Parse semicolon-terminated statements**

Teach `parse_statement` to parse:

```rust
break;
continue;
```

- [ ] **Step 3: Run compiler tests and verify remaining RED**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml loop_control
```

Expected: parsing succeeds, but codegen rejects or does not handle loop-control statements yet.

### Task 3: Lower Loop Control

**Files:**
- Modify: `native/ckl-compiler/src/codegen.rs`

- [ ] **Step 1: Add block outcomes and loop context**

Extend block outcomes so `return`, `break`, and `continue` all mark following statements unreachable, while only `return` satisfies function return requirements.

- [ ] **Step 2: Implement `break` and `continue` lowering**

Use a loop-context stack:

- `continue` emits `Instruction::Jump { target: loop_start }`;
- `break` emits placeholder `Instruction::Jump { target: usize::MAX }` and patches it to loop end.

- [ ] **Step 3: Run focused compiler tests**

Run:

```bash
cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml break_continue
```

Expected: focused loop-control tests pass.

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
git add docs/superpowers/specs/2026-05-14-rust-like-seed-loop-control-design.md docs/superpowers/plans/2026-05-14-rust-like-seed-loop-control.md native/ckl-compiler/src/ast.rs native/ckl-compiler/src/lexer.rs native/ckl-compiler/src/parser.rs native/ckl-compiler/src/codegen.rs native/ckl-compiler/tests/compiler_seed.rs
git commit -m "feat: add seed loop control"
```
