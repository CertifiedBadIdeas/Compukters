# Rux Init Load Protection Implementation Plan

> Issue: [#117](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/117)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reject `/bin/init.ruxe` when its declared load address overlaps protected kernel/boot handoff memory.

**Architecture:** ABI v0 keeps user programs at or above `0x8000`. The kernel loader must validate the init RUXE section metadata before copying payload bytes. Invalid placement uses the existing explicit `INIT LOAD FAILED` path and does not attempt another image.

**Tech Stack:** Rux kernel example in `native/rux-compiler`, Rust integration/source tests, `cargo test`.

---

### Task 1: Add Failing Protection Tests

**Files:**
- Modify: `native/rux-compiler/tests/rux_volume_cli.rs`

- [ ] **Step 1: Add source and integration tests**

Add a source-structure test requiring a protected-address guard in `execute_loaded_init_ruxe`. Add an integration test that installs a program RUXE at `/bin/init.ruxe` with `load_addr=0x3f20` and expects `INIT LOAD FAILED` with panic code `73`.

- [ ] **Step 2: Run RED**

Run: `cargo test --test rux_volume_cli protected_init_load`

Expected: tests fail because the guard does not exist yet.

### Task 2: Implement Guard

**Files:**
- Modify: `native/rux-compiler/examples/kernel/init_loader.rx`

- [ ] **Step 1: Reject protected load addresses**

Require `load_addr_i32 >= 0x8000` before copying init payload. Keep all failures on the existing `write_init_load_failed()` path.

- [ ] **Step 2: Run focused tests**

Run: `cargo test --test rux_volume_cli protected_init_load`

Expected: focused tests pass.

### Task 3: Verify and Commit

- [ ] Run `cargo fmt -- --check`.
- [ ] Run `cargo test --test rux_volume_cli`.
- [ ] Run `cargo test`.
- [ ] Commit with `feat(os): reject protected init load addresses`.
- [ ] Comment on `#117` and leave it open for remaining ABI/failure slices.
