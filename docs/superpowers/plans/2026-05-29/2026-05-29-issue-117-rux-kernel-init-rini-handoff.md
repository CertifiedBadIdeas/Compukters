# RINI Init Handoff Implementation Plan

> Issue: [#117](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/117)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the kernel write a minimal `RINI` handoff block before entering `/bin/init.ruxe`.

**Architecture:** `RKBI` remains the bootloader-to-kernel block at `0x3f00`. The kernel writes a separate `RINI` block at `0x3f20` after validating and copying init, immediately before `rux16_jump(entry_pc)`. Init does not need to read the block in this slice.

**Tech Stack:** Rux examples in `native/rux-compiler`, Rust integration/source-structure tests, `cargo test`.

---

### Task 1: Add RINI Source Tests

**Files:**
- Modify: `native/rux-compiler/tests/rux_volume_cli.rs`
- Test: `native/rux-compiler/tests/rux_volume_cli.rs`

- [ ] **Step 1: Write the failing test**

Add `rux16_init_loader_source_writes_init_handoff_info` near the existing RKBI tests. Assert that `examples/kernel/init_loader.rx` defines `write_init_handoff_info`, stores `RINI` magic at `0x3f20`, stores version/size `0x00180001`, passes `root_start_lba`, init RUXE size, and `entry_pc`, and calls the writer before `rux16_jump(entry_pc)`.

- [ ] **Step 2: Run test to verify it fails**

Run: `cargo test --test rux_volume_cli init_handoff_info`

Expected: the new test fails because `write_init_handoff_info` does not exist yet.

### Task 2: Implement RINI Writer

**Files:**
- Modify: `native/rux-compiler/examples/kernel/init_loader.rx`
- Test: `native/rux-compiler/tests/rux_volume_cli.rs`

- [ ] **Step 1: Write minimal implementation**

Add `write_init_handoff_info(root_start_lba, init_ruxe_size_bytes, init_entry_pc)` to the kernel example. Change `execute_loaded_init_ruxe` to accept `root_start_lba`, read the loaded init RUXE size from `0xa008`, and write the `RINI` block immediately before jumping.

- [ ] **Step 2: Run focused tests**

Run: `cargo test --test rux_volume_cli init_handoff_info`

Expected: the focused `RINI` test passes.

### Task 3: Verify Boot Chain

**Files:**
- Modify: none
- Test: `native/rux-compiler/tests/rux_volume_cli.rs`

- [ ] **Step 1: Run integration test**

Run: `cargo test --test rux_volume_cli rux_volume_boot_kernel_and_init_executes_init_from_root_ruxfs`

Expected: the BIOS -> bootloader -> kernel -> init path still reaches init.

- [ ] **Step 2: Run full compiler crate verification**

Run: `cargo fmt -- --check`

Expected: no formatting changes required.

Run: `cargo test`

Expected: all `native/rux-compiler` tests pass.

### Task 4: Commit and Update Roadmap

**Files:**
- Modify: issue `#117`

- [ ] **Step 1: Commit**

Run:

```bash
git add docs/superpowers/specs/2026-05-29/2026-05-29-issue-117-rux-kernel-init-abi-design.md docs/superpowers/plans/2026-05-29/2026-05-29-issue-117-rux-kernel-init-rini-handoff.md native/rux-compiler/examples/kernel/init_loader.rx native/rux-compiler/tests/rux_volume_cli.rs
git commit -m "feat(os): pass RINI handoff to init"
```

- [ ] **Step 2: Update issue**

Add a short `gh issue comment 117` note with the commit hash and verification commands. Leave the issue open for the remaining negative tests/follow-up slices.
