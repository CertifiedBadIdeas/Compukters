# Rux Init Failure Behavior Implementation Plan

> Issue: [#117](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/117)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make missing or invalid `/bin/init.ruxe` fail explicitly and leave no stale `RINI` handoff block behind.

**Architecture:** `RINI` is valid only when the kernel is about to enter init. All init load failures use `INIT LOAD FAILED`, panic code `73`, and clear the `0x3f20` handoff block so later readers cannot mistake stale RAM for a valid handoff.

**Tech Stack:** Rux kernel example in `native/rux-compiler`, Rust boot-chain tests, `cargo test`.

---

### Task 1: Add Failing Failure-Path Tests

**Files:**
- Modify: `native/rux-compiler/tests/rux_volume_cli.rs`

- [ ] **Step 1: Add missing-init and wrong-ABI tests**

Create bootable storage volumes with the normal BIOS -> bootloader -> kernel chain. Before running, write stale `RINI` bytes at `0x3f20`. Verify both missing `/bin/init.ruxe` and kernel-ABI `/bin/init.ruxe` halt with `INIT LOAD FAILED`, panic code `73`, and cleared `RINI` magic.

- [ ] **Step 2: Run RED**

Run: `cargo test --test rux_volume_cli init_failure`

Expected: tests fail because `write_init_load_failed` does not clear stale `RINI` yet.

### Task 2: Clear RINI on Init Failure

**Files:**
- Modify: `native/rux-compiler/examples/kernel/init_loader.rx`

- [ ] **Step 1: Add clear helper**

Add `clear_init_handoff_info()` and call it at the start of `write_init_load_failed()`.

- [ ] **Step 2: Run focused tests**

Run: `cargo test --test rux_volume_cli init_failure`

Expected: focused tests pass.

### Task 3: Verify and Commit

- [ ] Run `cargo fmt -- --check`.
- [ ] Run `cargo test --test rux_volume_cli`.
- [ ] Run `cargo test`.
- [ ] Commit with `feat(os): clear init handoff on load failure`.
- [ ] Comment on `#117` and leave it open only if remaining acceptance criteria still need work.
