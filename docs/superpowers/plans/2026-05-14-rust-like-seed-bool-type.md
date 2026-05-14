# Rust-Like Seed Bool Type Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add real `bool` typing and boolean literals to the Rust-like seed compiler.

**Architecture:** Extend the compiler AST/type model and reuse existing low VM bool register semantics. Do not add VM instructions in this slice.

**Tech Stack:** Rust, `native/ckl-compiler`, `native/ckl-vm`, offline Cargo tests.

---

## File Structure

- Modify `native/ckl-compiler/src/lexer.rs`: add `bool`, `true`, and `false` tokens.
- Modify `native/ckl-compiler/src/ast.rs`: add type annotations and boolean expressions.
- Modify `native/ckl-compiler/src/parser.rs`: parse `bool` types and boolean literals.
- Modify `native/ckl-compiler/src/codegen.rs`: add bool expression values, local types, return types, and argument checking.
- Modify `native/ckl-compiler/tests/compiler_seed.rs`: add bool tests and update integer-condition expectations.

## Tasks

### Task 1: RED tests

- [ ] Add lexer tests for `bool`, `true`, and `false`.
- [ ] Add compiler tests for bool returns, locals, conditions, function arguments, and type errors.
- [ ] Run `cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml` and confirm failure on missing bool support.

### Task 2: Parser and AST

- [ ] Add tokens.
- [ ] Add a small parsed type enum.
- [ ] Store parameter/local/return types.
- [ ] Parse bool literals.

### Task 3: Codegen

- [ ] Add `ExprValue::Bool` and `ValueType::Bool`.
- [ ] Make comparisons return bool.
- [ ] Make `if` and `while` require bool.
- [ ] Lower bool literals through `I32Const 1/0`.
- [ ] Lower bool returns through `ReturnBool`.
- [ ] Enforce matching assignment and argument types.

### Task 4: Verification

- [ ] Run `cargo fmt --manifest-path native/ckl-compiler/Cargo.toml --check`.
- [ ] Run `cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml`.
- [ ] Run `cargo test --offline --manifest-path native/ckl-vm/Cargo.toml`.
- [ ] Commit the completed slice.

## Self-Review

- No placeholder steps remain.
- The plan does not add boolean operators.
- The plan keeps low-image ABI unchanged.
- The tests prove both accepted bool programs and rejected implicit conversions.
