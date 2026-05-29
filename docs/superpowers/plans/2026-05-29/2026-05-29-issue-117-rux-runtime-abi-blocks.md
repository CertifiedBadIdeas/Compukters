# Rux Runtime ABI Blocks Implementation Plan

> Issue: [#117](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/117)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Verify `RKBI` and `RINI` contents after a real BIOS -> bootloader -> kernel -> init guest execution.

**Architecture:** Source tests keep guarding the intended writer functions. This slice adds runtime assertions over guest RAM and fixes the RUXE size fields to use the actual loaded RUXE file size, computed from the fixed single-load RUXE section.

**Tech Stack:** Rux compiler examples, Rux VM guest RAM reads, Rust integration tests, `cargo test`.

---

### Task 1: Add Runtime ABI Block Test

**Files:**
- Modify: `native/rux-compiler/tests/rux_volume_cli.rs`

- [ ] **Step 1: Add assertions after happy-path boot**

After `INIT OK`, read guest RAM at `0x3f00` and `0x3f20`. Assert `RKBI`, `RINI`, version/size words, root LBA `33`, actual compiled RUXE file sizes, init entry PC, and zero flags.

- [ ] **Step 2: Run RED**

Run: `cargo test --test rux_volume_cli runtime_handoff_blocks`

Expected: test fails because current size fields contain the RUXE ISA word, not actual file size.

### Task 2: Fix Size Fields

**Files:**
- Modify: `native/rux-compiler/examples/boot/kernel_loader.rx`
- Modify: `native/rux-compiler/examples/kernel/init_loader.rx`

- [ ] **Step 1: Compute RUXE file size from section metadata**

In both loaders, compute `payload_offset_i32 + payload_size_i32` after section validation and write that value into the ABI block.

- [ ] **Step 2: Run focused test**

Run: `cargo test --test rux_volume_cli runtime_handoff_blocks`

Expected: focused test passes.

### Task 3: Verify and Finish Issue

- [ ] Run `cargo fmt -- --check`.
- [ ] Run `cargo test --test rux_volume_cli`.
- [ ] Run `cargo test`.
- [ ] Commit with `feat(os): verify runtime handoff blocks`.
- [ ] Update `#117`; if acceptance criteria are complete, close it and move Roadmap status to Done.
