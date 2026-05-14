# Rust-Like Seed Boolean Operators Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `!`, `&&`, and `||` to the Rust-like seed compiler with short-circuit semantics.

**Architecture:** Extend the compiler only. Reuse existing low-image `I32Const`, `I32Eq`, `I32Move`, `Jump`, and `JumpIfFalse` instructions.

**Tech Stack:** Rust, `native/rux-compiler`, `native/rux-vm`, offline Cargo tests.

---

## File Structure

- Modify `native/rux-compiler/src/lexer.rs`: add `Bang`, `AndAnd`, and `OrOr` tokens.
- Modify `native/rux-compiler/src/ast.rs`: add unary/logical expression nodes.
- Modify `native/rux-compiler/src/parser.rs`: add logical precedence and unary `!`.
- Modify `native/rux-compiler/src/codegen.rs`: lower `!`, `&&`, and `||`.
- Modify `native/rux-compiler/tests/compiler_seed.rs`: add lexer, lowering, e2e, short-circuit, and type-error tests.

## Tasks

### Task 1: RED tests

- [ ] Add tests for lexer tokens and boolean operator behavior.
- [ ] Run `cargo test --offline --manifest-path native/rux-compiler/Cargo.toml` and confirm missing-token or missing-AST failures.

### Task 2: Parser and AST

- [ ] Add token kinds.
- [ ] Add AST nodes.
- [ ] Parse `||`, `&&`, and unary `!` with the intended precedence.

### Task 3: Codegen

- [ ] Add bool-only lowering for `!`.
- [ ] Add short-circuit lowering for `&&`.
- [ ] Add short-circuit lowering for `||`.
- [ ] Enforce bool operands and reject integer operands.

### Task 4: Verification

- [ ] Run `cargo fmt --manifest-path native/rux-compiler/Cargo.toml --check`.
- [ ] Run `cargo test --offline --manifest-path native/rux-compiler/Cargo.toml`.
- [ ] Run `cargo test --offline --manifest-path native/rux-vm/Cargo.toml`.
- [ ] Commit the completed slice.

## Self-Review

- No placeholders remain.
- `&&` and `||` are short-circuiting, not bitwise.
- No low-image ABI change is required.
