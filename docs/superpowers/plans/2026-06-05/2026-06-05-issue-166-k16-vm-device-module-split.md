# K16 VM Device Module Split Implementation Plan

> Issue: [#166](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/166)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split K16 computer MMIO devices into focused modules without changing VM behavior.

**Architecture:** Keep `computer/devices.rs` as the stable import surface and move implementation code into focused submodules under `computer/devices/`. This is a mechanical readability refactor; ABI and runtime behavior remain unchanged.

**Tech Stack:** Rust 2021, `k16-vm`, existing `MmioDevice` and `MachineMemory` interfaces.

---

### Task 1: Prepare Local Scratch Ignore

**Files:**
- Modify: `.gitignore`

- [x] **Step 1: Ignore `.agents/tmp/`**

Add `.agents/tmp/` to `.gitignore` near other local runtime/worktree paths so agent scratch files do not become repository history.

### Task 2: Split Device Families

**Files:**
- Modify: `rust/host/k16-vm/src/computer/devices.rs`
- Create: `rust/host/k16-vm/src/computer/devices/bios.rs`
- Create: `rust/host/k16-vm/src/computer/devices/control.rs`
- Create: `rust/host/k16-vm/src/computer/devices/serial.rs`
- Create: `rust/host/k16-vm/src/computer/devices/text_display.rs`
- Create: `rust/host/k16-vm/src/computer/devices/framebuffer.rs`
- Create: `rust/host/k16-vm/src/computer/devices/storage.rs`

- [ ] **Step 1: Move BIOS flash code**

Move `BiosFlashDevice` and its `MmioDevice` implementation into `devices/bios.rs`.

- [ ] **Step 2: Move control code**

Move `ComputerControlDevice` and its `MmioDevice` implementation into `devices/control.rs`.

- [ ] **Step 3: Move serial code**

Move `DebugSerialDevice`, `SerialInputDevice`, and their `MmioDevice` implementations into `devices/serial.rs`.

- [ ] **Step 4: Move display code**

Move `ComputerTextDisplaySnapshot`, `TextDisplayDevice`, and its `MmioDevice` implementation into `devices/text_display.rs`.

- [ ] **Step 5: Move framebuffer code**

Move `FramebufferDevice` and its `MmioDevice` implementation into `devices/framebuffer.rs`.

- [ ] **Step 6: Move storage code**

Move `StorageMedia`, `K16VolumeFileStorageMedia`, `InMemoryStorageMedia`, `StoragePortDevice`, helper functions, storage tests, and related implementations into `devices/storage.rs`.

- [ ] **Step 7: Preserve import surface**

Make `devices.rs` declare the private submodules and re-export the existing device types from `crate::computer::devices`.

### Task 3: Verify

**Files:**
- Modify if needed: Rust imports affected by formatting or visibility.

- [ ] **Step 1: Format check**

Run: `cd rust/host/k16-vm && cargo fmt -- --check`

- [ ] **Step 2: Full Rust VM tests**

Run: `cd rust/host/k16-vm && cargo test`

- [ ] **Step 3: Diff whitespace check**

Run: `git diff --check`

### Task 4: Close Roadmap Slice

**Files:**
- Modified by pre-commit if benchmark hook runs: `docs/benchmarks/k16-vm-current.txt`

- [ ] **Step 1: Stage the scoped changes**

Stage `.gitignore`, spec/plan files, device split files, and any benchmark snapshot refreshed by the pre-commit hook.

- [ ] **Step 2: Commit**

Commit message: `refactor(vm): split K16 computer device modules`

- [ ] **Step 3: Close issue**

If verification passes and the commit succeeds, close issue #166 as completed and set Roadmap status to Done.
