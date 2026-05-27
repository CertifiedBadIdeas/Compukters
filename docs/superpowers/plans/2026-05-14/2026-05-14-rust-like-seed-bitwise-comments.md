# Rust-Like Seed Bitwise Comments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add line comments and `i32` bitwise operators to the Rust-like seed compiler and low VM.

**Architecture:** Extend the lexer/parser/AST/codegen pipeline in `native/rux-compiler`, then add the two missing low-image instructions to `native/rux-vm`. Keep low-image tags additive so existing generated fixtures remain stable.

**Tech Stack:** Rust, `native/rux-compiler`, `native/rux-vm`, offline Cargo tests.

---

## File Structure

- Modify `native/rux-compiler/src/lexer.rs`: comment skipping and new token kinds.
- Modify `native/rux-compiler/src/ast.rs`: bitwise `BinaryOp` variants.
- Modify `native/rux-compiler/src/parser.rs`: precedence levels for `|`, `^`, `&`, `<<`, and `>>`.
- Modify `native/rux-compiler/src/codegen.rs`: const eval and low-image lowering.
- Modify `native/rux-vm/src/low_image.rs`: additive `I32BitAnd` and `I32BitOr` decode tags.
- Modify `native/rux-vm/src/low_image_runner.rs`: executable ops, validation, lowering, execution, and block analysis for the new instructions.
- Modify `native/rux-compiler/tests/compiler_seed.rs`: lexer, lowering, const, and end-to-end tests.
- Modify `native/rux-vm/tests/low_image_runner.rs`: runner test for the new VM ops.

## Tasks

### Task 1: RED tests

- [ ] Add compiler tests that expect `//`, `&`, `|`, `^`, `<<`, and `>>`.
- [ ] Add VM runner test that expects `I32BitAnd` and `I32BitOr`.
- [ ] Run `cargo test --offline --manifest-path native/rux-compiler/Cargo.toml` and confirm it fails on missing token/operator support.
- [ ] Run `cargo test --offline --manifest-path native/rux-vm/Cargo.toml` and confirm it fails on missing low-image instructions.

### Task 2: Low VM support

- [ ] Add `I32BitAnd` and `I32BitOr` to `low_image.rs` with tags `26` and `27`.
- [ ] Add executable operations and immediate variants in `low_image_runner.rs`.
- [ ] Wire validation/read/write/register analysis.
- [ ] Wire pre-execution lowering and runtime execution.
- [ ] Run `cargo test --offline --manifest-path native/rux-vm/Cargo.toml` and confirm VM tests pass.
- [ ] Commit VM support.

### Task 3: Compiler support

- [ ] Add lexer token kinds and `//` skipping.
- [ ] Add AST binary operators.
- [ ] Refactor parser precedence to include bitwise levels.
- [ ] Add codegen lowering to low-image instructions.
- [ ] Add const evaluator support.
- [ ] Run `cargo test --offline --manifest-path native/rux-compiler/Cargo.toml` and confirm compiler tests pass.
- [ ] Commit compiler support.

### Task 4: Verification

- [ ] Run `cargo fmt --manifest-path native/rux-compiler/Cargo.toml --check`.
- [ ] Run `cargo fmt --manifest-path native/rux-vm/Cargo.toml --check`.
- [ ] Run `cargo test --offline --manifest-path native/rux-compiler/Cargo.toml`.
- [ ] Run `cargo test --offline --manifest-path native/rux-vm/Cargo.toml`.
- [ ] Commit docs if they were not committed earlier.

## Self-Review

- The plan uses TDD and keeps each step small.
- No placeholder tasks are left.
- The plan does not introduce bytecode compatibility layers or Kotlin fallbacks.
- Existing low-image instruction tags remain stable.
