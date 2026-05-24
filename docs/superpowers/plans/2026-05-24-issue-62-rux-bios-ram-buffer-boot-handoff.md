# Rux BIOS RAM-Buffer Boot Handoff Implementation Plan

> Issue: [#62](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/62)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first BIOS boot handoff slice: a Rux computer can replace its BIOS CPU with a RUXI image that BIOS has already copied into guest RAM.

**Architecture:** Keep storage and boot policy outside this first slice. `ComputerMachine` owns the machine-state transition: validate a guest RAM range, copy those bytes, decode RUXI, load its sections at the profile program base, and replace the existing boot CPU context. `RuxComputerHandle` exposes a narrow wrapper so native-runtime/JNI tests and later boot0 transport can call the same implementation.

**Tech Stack:** Rust `native/rux-vm`, existing `Image`/`decode_image`, `LowImageVm::create_cpu_context`, `MachineMemory`, and native Rust tests.

---

## File Structure

- Modify `native/rux-vm/src/computer/machine.rs`: add `BootHandoffError`, `boot_handoff_ruxi_from_ram`, RAM-range validation, RUXI decode, and boot CPU replacement.
- Modify `native/rux-vm/src/computer/handle.rs`: expose `RuxComputerHandle::boot_handoff_ruxi_from_guest_ram`.
- Modify `native/rux-vm/src/computer/mod.rs` and `native/rux-vm/src/lib.rs`: re-export `BootHandoffError`.
- Modify `native/rux-vm/tests/rux_computer.rs`: add public-handle regression tests for successful handoff and invalid buffers.

## Task 1: Machine Boot Handoff API

**Files:**
- Modify: `native/rux-vm/src/computer/machine.rs`
- Modify: `native/rux-vm/src/computer/handle.rs`
- Modify: `native/rux-vm/src/computer/mod.rs`
- Modify: `native/rux-vm/src/lib.rs`
- Test: `native/rux-vm/tests/rux_computer.rs`

- [x] **Step 1: Write the failing success test**

Add a test that creates a BIOS image which halts with `1`, writes a second image that halts with `77` into guest RAM through a test-only handle helper, calls `boot_handoff_ruxi_from_guest_ram`, then verifies the boot CPU now halts with `77`.

- [x] **Step 2: Run the focused test to verify RED**

Run: `cargo test --test rux_computer rux_computer_handle_boot_handoff_replaces_bios_cpu_from_guest_ram`

Expected: FAIL because `RuxComputerHandle::write_guest_ram_bytes` and `boot_handoff_ruxi_from_guest_ram` do not exist yet.

- [x] **Step 3: Implement minimal success path**

Add:

```rust
pub fn boot_handoff_ruxi_from_ram(
    &mut self,
    image_addr: u32,
    image_len: u32,
    slice_budget_nanos: u64,
) -> Result<CpuId, BootHandoffError>
```

The method must reject missing boot CPU, zero length, overflow, and ranges outside guest RAM. On success it copies the bytes from RAM, decodes them with `decode_image`, validates memory size through the existing spawn path rules, loads image sections at `program_base`, replaces `self.cpus[boot_cpu]`, and returns the same boot CPU id.

- [x] **Step 4: Run the focused success test to verify GREEN**

Run: `cargo test --test rux_computer rux_computer_handle_boot_handoff_replaces_bios_cpu_from_guest_ram`

Expected: PASS.

- [x] **Step 5: Write failing rejection tests**

Add tests for zero-length handoff, out-of-bounds RAM range, and invalid RUXI bytes. Each test must also verify the original BIOS CPU still runs after the failed handoff.

- [x] **Step 6: Run rejection tests to verify RED/GREEN as needed**

Run: `cargo test --test rux_computer boot_handoff`

Expected: PASS after the error mapping and no-state-change behavior are implemented.

## Task 2: Document The Implemented Slice

**Files:**
- Modify: `docs/superpowers/specs/2026-05-24-issue-62-rux-bios-ram-buffer-boot-handoff-design.md`
- Modify: `docs/superpowers/plans/2026-05-24-issue-62-rux-bios-ram-buffer-boot-handoff.md`

- [x] **Step 1: Update spec status**

Change spec status from `Draft for review.` to `Accepted; first implementation slice covers the VM-side RAM-buffer handoff API.`

- [x] **Step 2: Mark completed plan steps**

Mark completed checkboxes for Task 1 steps that were actually executed.

- [x] **Step 3: Run verification**

Run: `cargo test --test rux_computer boot_handoff`

Expected: PASS.

## Task 3: Commit Boundary

**Files:**
- Stage only the #62 plan/spec and Rust files touched by this slice.

- [x] **Step 1: Review status**

Run: `git status --short`

Expected: only intended #62 files plus any pre-existing untracked #68 spec.

- [ ] **Step 2: Commit when requested**

Do not commit automatically unless the user explicitly asks. Suggested message:

```bash
git commit -m "feat(vm): add RAM-buffer boot handoff"
```
