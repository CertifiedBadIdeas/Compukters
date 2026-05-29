# RINI-Consuming Init Implementation Plan

> Issue: [#118](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/118)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Rux init example that reads and validates the `RINI` kernel-to-init handoff block before reporting success.

**Architecture:** The kernel already writes `RINI` at `0x3f20`. This slice keeps the kernel unchanged and moves the next ABI responsibility into userspace: `/bin/init.ruxe` reads the block directly from RAM, validates the expected fields, and only then writes `INIT OK`.

**Tech Stack:** Rux example source, Rust CLI/boot-chain tests, `cargo test`.

---

### Task 1: Add RED Tests

**Files:**
- Modify: `native/rux-compiler/tests/rux_volume_cli.rs`

- [x] **Step 1: Add source-structure test**

Assert that `examples/init/rini_init.rx` reads `0x3f20`, checks magic `0x494e4952`, version/size `0x00180001`, root LBA `33`, entry PC `0x8000`, flags `0`, and has explicit success/failure writers.

- [x] **Step 2: Add boot-chain test**

Compile the example as the default program target, install it as `/bin/init.ruxe`, boot through BIOS -> bootloader -> kernel -> init, and assert row 0 is `INIT OK` with panic code `0`.

- [x] **Step 3: Run RED**

Run: `cargo test --test rux_volume_cli rini_init`

Expected: tests fail because the example does not exist yet.

### Task 2: Implement Example

**Files:**
- Create: `native/rux-compiler/examples/init/rini_init.rx`

- [x] **Step 1: Add minimal Rux init**

Read the `RINI` block with `ptr<i32>`, validate fields, write `INIT OK` through `display0`, otherwise write `INIT FAILED` and panic code `73`.

- [x] **Step 2: Run focused tests**

Run: `cargo test --test rux_volume_cli rini_init`

Expected: focused tests pass.

### Task 3: Verify and Commit

- [x] Run `cargo fmt -- --check`.
- [x] Run `cargo test --test rux_volume_cli`.
- [x] Run `cargo test`.
- [x] Commit with `feat(os): make init consume RINI handoff`.
- [x] Update `#118` with commit and verification.
