# Rux Terminal Runner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a runnable Rux firmware demo path with a small terminal-like console UI.

**Architecture:** Keep the runner inside `native/ckl-compiler` because it compiles Rux source and then boots it on `ComputerMachine`. Expose a small library API for tests and a `rux-run` binary for manual use. Render a simple ASCII terminal frame with debug serial output and machine status, avoiding external dependencies.

**Tech Stack:** Rust std, `ckl-compiler`, `ckl-vm::computer_machine`, Cargo tests run offline.

---

### Task 1: Runner Library API

**Files:**
- Create: `native/ckl-compiler/src/runner.rs`
- Modify: `native/ckl-compiler/src/lib.rs`
- Create: `native/ckl-compiler/tests/rux_runner.rs`

- [ ] Add failing tests for `run_source` and terminal UI rendering.
- [ ] Implement `RuxRunReport`, `run_source`, and `render_terminal_ui`.
- [ ] Re-export runner API from `lib.rs`.

### Task 2: CLI Binary And Demo Firmware

**Files:**
- Create: `native/ckl-compiler/src/bin/rux-run.rs`
- Create: `native/ckl-compiler/examples/firmware/terminal.rux`
- Modify: `native/ckl-compiler/tests/rux_runner.rs`

- [ ] Add a test that loads the demo firmware source and verifies the report output.
- [ ] Implement `rux-run` with usage `cargo run --bin rux-run -- <path.rux>`.
- [ ] Add a demo firmware that writes a visible line to `DEBUG_WRITE`.

### Task 3: Verification And Commit

- [ ] Run `cargo fmt --manifest-path native/ckl-compiler/Cargo.toml --check`.
- [ ] Run `cargo test --offline --manifest-path native/ckl-compiler/Cargo.toml`.
- [ ] Run `cargo run --offline --manifest-path native/ckl-compiler/Cargo.toml --bin rux-run -- native/ckl-compiler/examples/firmware/terminal.rux`.
- [ ] Commit all changes.
