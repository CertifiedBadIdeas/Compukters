# K16 CLI Surface Implementation Plan

> Issue: [#147](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/147)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `k16` binary for Kraft16 machine/artifact commands while keeping the Rux language CLI named `rux`.

**Architecture:** Move the shared command implementation out of `src/bin/rux.rs` into a library CLI module. Keep `rux` as the language-facing binary with `compile` and `check`; add `k16` as the machine-facing binary with `link`, `runtime`, `run`, `disasm`, `inspect`, `fs`, and `volume`.

**Tech Stack:** Rust 2021, Cargo integration tests, existing `native/rux-compiler` modules.

---

### Task 1: Add Failing CLI Contract Test

**Files:**
- Modify: `native/rux-compiler/tests/rux_public_cli_surface.rs`

- [x] **Step 1: Write the failing test**

Add tests that expect Cargo to expose a `k16` binary, expect `k16 compile` to fail without advertising the Rux language compiler, and expect `k16 link` usage to advertise `k16 link`.

- [x] **Step 2: Run the test to verify RED**

Run:

```bash
cargo test --manifest-path native/rux-compiler/Cargo.toml --test rux_public_cli_surface
```

Expected before implementation: FAIL because `CARGO_BIN_EXE_k16` is not available.

### Task 2: Split Shared CLI Implementation

**Files:**
- Create: `native/rux-compiler/src/cli.rs`
- Modify: `native/rux-compiler/src/lib.rs`
- Modify: `native/rux-compiler/src/bin/rux.rs`
- Create: `native/rux-compiler/src/bin/k16.rs`

- [x] **Step 1: Move command code into `cli.rs`**

Move the existing command handlers from `src/bin/rux.rs` into `src/cli.rs`.

- [x] **Step 2: Add two public entrypoints**

Expose `run_rux_cli(args)` and `run_k16_cli(args)`. `run_rux_cli` keeps the existing command set. `run_k16_cli` accepts only machine/artifact commands and rejects Rux-language commands with `k16` usage.

- [x] **Step 3: Keep thin binaries**

`src/bin/rux.rs` should call `rux_compiler::cli::run_rux_cli`. `src/bin/k16.rs` should call `rux_compiler::cli::run_k16_cli`.

### Task 3: Verify And Commit

**Files:**
- All files above

- [x] **Step 1: Run focused CLI tests**

Run:

```bash
cargo test --manifest-path native/rux-compiler/Cargo.toml --test rux_public_cli_surface
```

Expected: PASS.

- [x] **Step 2: Run full Rust crate tests**

Run:

```bash
cargo test --manifest-path native/rux-compiler/Cargo.toml
```

Expected: PASS.

- [x] **Step 3: Run formatting and whitespace checks**

Run:

```bash
cargo fmt --manifest-path native/rux-compiler/Cargo.toml --check
git diff --check
```

Expected: no output.

- [x] **Step 4: Commit**

Run:

```bash
git add native/rux-compiler/src/cli.rs native/rux-compiler/src/lib.rs native/rux-compiler/src/bin/rux.rs native/rux-compiler/src/bin/k16.rs native/rux-compiler/tests/rux_public_cli_surface.rs docs/superpowers/plans/2026-05-31/2026-05-31-issue-147-k16-cli-surface.md
git commit -m "feat(tooling): add k16 machine CLI"
```
