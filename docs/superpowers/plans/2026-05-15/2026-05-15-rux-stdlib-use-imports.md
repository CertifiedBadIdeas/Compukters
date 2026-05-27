# Rux Stdlib Use Imports Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add explicit Rust-like `use std::module::function;` imports for a tiny Rux standard library.

**Architecture:** Add `use` and `pub fn` syntax to the parser, then insert a small resolver pass between parsing and codegen. The resolver loads bundled stdlib modules from `.rx` source strings, imports only requested public functions, rejects unknown/private/duplicate imports, and then lets the existing codegen compile a single resolved program.

**Tech Stack:** Rust 2021, `native/rux-compiler`, Rux parser/codegen tests, `rux-vm::ComputerMachine`, Cargo tests.

---

### Task 1: Parse Use Declarations And Public Functions

**Files:**
- Modify: `native/rux-compiler/src/lexer.rs`
- Modify: `native/rux-compiler/src/ast.rs`
- Modify: `native/rux-compiler/src/parser.rs`
- Test: `native/rux-compiler/tests/compiler_seed.rs`

- [ ] Add `use`, `pub`, and `::` tokens.
- [ ] Add `UseDecl { path: Vec<String> }` and `Visibility`.
- [ ] Parse top-level `use std::mem::copy;`.
- [ ] Parse `pub fn copy(...)` for stdlib modules.
- [ ] Verify lexer/parser tests fail before implementation and pass after implementation.

### Task 2: Add Resolver And Bundled Stdlib

**Files:**
- Create: `native/rux-compiler/src/resolver.rs`
- Create: `native/rux-compiler/src/stdlib.rs`
- Create: `native/rux-compiler/stdlib/std/mem.rx`
- Create: `native/rux-compiler/stdlib/std/io.rx`
- Modify: `native/rux-compiler/src/lib.rs`
- Test: `native/rux-compiler/tests/compiler_seed.rs`

- [ ] Add tests for `use std::mem::copy;` and `use std::io::write_bytes;`.
- [ ] Add rejection tests for missing import, unknown std path, and private std function.
- [ ] Resolve imported public std functions into the user program before codegen.
- [ ] Keep imports explicit: calling `copy` without `use` must remain an unknown function.

### Task 3: Switch Examples To Stdlib Imports

**Files:**
- Modify: `native/rux-compiler/examples/firmware/copy.rx`
- Modify: `native/rux-compiler/examples/firmware/terminal.rx`
- Test: `native/rux-compiler/tests/rux_runner.rs`

- [ ] Replace local `copy`/`write_bytes` helpers with explicit `use std::...` imports.
- [ ] Run the runner tests to prove examples still execute on `ComputerMachine`.

### Task 4: Verify And Commit

**Files:**
- All touched files.

- [ ] Run `cargo fmt --manifest-path native/rux-compiler/Cargo.toml`.
- [ ] Run `cargo test --offline --manifest-path native/rux-compiler/Cargo.toml`.
- [ ] Commit as `feat: add explicit rux std imports`.
