# Rux16 BIOS Storage Read Implementation Plan

> Issue: [#62](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/62)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove that BIOS executing from Rux16 BIOS flash can read storage0 into guest RAM through MMIO.

**Architecture:** Add a direct Rux16 BIOS-flash constructor that accepts in-memory storage0 media. Add an integration test with raw Rux16 BIOS words: configure storage0 registers, issue `READ_BLOCKS`, load the resulting RAM bytes, and report them through debug/control MMIO. Keep execution fully guest-side after reset; no LowImage and no host decode path.

**Tech Stack:** Rust 2021, `RuxComputerHandle`, `ComputerMachineProfile`, `StoragePortDevice`, Rux16 raw instruction helpers, native integration tests.

---

## File Structure

- Create `docs/superpowers/specs/2026-05-25-issue-62-rux16-bios-storage-read-design.md`: design record for this slice.
- Create `docs/superpowers/plans/2026-05-25-issue-62-rux16-bios-storage-read.md`: this plan.
- Modify `native/rux-vm/tests/rux_computer.rs`: add the failing BIOS storage read test and helper encoders.
- Modify `native/rux-vm/src/computer/machine.rs`: add a profile-based Rux16 BIOS flash creation helper.
- Modify `native/rux-vm/src/computer/handle.rs`: expose `create_rux16_bios_flash_with_storage0_media(...)`.

## Task 1: Documentation

- [ ] **Step 1: Save the spec and plan**

Use `apply_patch` to add the files above.

- [ ] **Step 2: Verify docs whitespace**

Run: `git diff --check -- docs/superpowers/specs/2026-05-25-issue-62-rux16-bios-storage-read-design.md docs/superpowers/plans/2026-05-25-issue-62-rux16-bios-storage-read.md`

Expected: exit 0.

- [ ] **Step 3: Commit docs**

```bash
git add docs/superpowers/specs/2026-05-25-issue-62-rux16-bios-storage-read-design.md docs/superpowers/plans/2026-05-25-issue-62-rux16-bios-storage-read.md
git commit -m "docs(vm): plan Rux16 BIOS storage read"
```

## Task 2: Failing Test

- [ ] **Step 1: Add the BIOS storage read test**

Add this test to `native/rux-vm/tests/rux_computer.rs`:

```rust
#[test]
fn rux_computer_handle_rux16_bios_flash_reads_storage0_block_into_ram() {
    let bios = rux16_words(&rux16_storage_read_bios_words());
    let mut media = vec![0; 512];
    media[0..3].copy_from_slice(b"RUX");
    let mut handle = RuxComputerHandle::create_rux16_bios_flash_with_storage0_media(
        &bios,
        64 * 1024,
        256,
        media,
    )
    .expect("Rux16 BIOS flash computer creates with storage0 media");

    assert_eq!(handle.run_rux16_until_signal().unwrap(), Rux16Signal::Halt);
    assert_eq!(handle.debug_output_bytes(), b"RUX");
    assert_eq!(
        handle.control(),
        RuxComputerControl {
            status: ComputerMachine::STATUS_HALTED,
            exit_code: 0,
            panic_code: 2,
        },
    );
}
```

- [ ] **Step 2: Add helper encoders**

Add `rux16_load8`, `rux16_load32`, and `rux16_storage_read_bios_words` helpers near the existing Rux16 test helpers. The BIOS should write storage0 registers, issue read, load three bytes from RAM address `512`, write them to debug, load `STORAGE0_STATUS`, store it to `CONTROL_PANIC_CODE`, and halt.

- [ ] **Step 3: Run focused test to verify RED**

Run: `cargo test --test rux_computer --manifest-path native/rux-vm/Cargo.toml rux_computer_handle_rux16_bios_flash_reads_storage0_block_into_ram`

Expected: compile failure for missing `RuxComputerHandle::create_rux16_bios_flash_with_storage0_media`.

## Task 3: Implementation

- [ ] **Step 1: Add profile-based Rux16 BIOS flash creation**

Refactor `ComputerMachine::from_rux16_bios_flash(...)` to call a private helper that accepts `ComputerMachineProfile`. Add `ComputerMachine::from_rux16_bios_flash_with_profile(...)` only if it needs to be public for handle use; otherwise keep it private.

- [ ] **Step 2: Add handle constructor with storage media**

Add `RuxComputerHandle::create_rux16_bios_flash_with_storage0_media(bios_flash, memory_size, max_steps, storage0_media)`. It should use `ComputerMachineProfile::computer_v1_with_storage0_media(memory_size, storage0_media, false)` and the Rux16 BIOS flash machine creation helper.

- [ ] **Step 3: Run focused test to verify GREEN**

Run: `cargo test --test rux_computer --manifest-path native/rux-vm/Cargo.toml rux_computer_handle_rux16_bios_flash_reads_storage0_block_into_ram`

Expected: test passes.

## Task 4: Verification And Commit

- [ ] **Step 1: Format touched Rust files**

Run: `rustfmt native/rux-vm/src/computer/machine.rs native/rux-vm/src/computer/handle.rs native/rux-vm/tests/rux_computer.rs`

- [ ] **Step 2: Run focused computer tests**

Run: `cargo test --test rux_computer --manifest-path native/rux-vm/Cargo.toml`

Expected: all `rux_computer` tests pass.

- [ ] **Step 3: Run full native VM tests**

Run: `cargo test --manifest-path native/rux-vm/Cargo.toml`

Expected: all native `rux-vm` tests pass.

- [ ] **Step 4: Check whitespace**

Run: `git diff --check`

Expected: exit 0.

- [ ] **Step 5: Commit code**

```bash
git add native/rux-vm/src/computer/machine.rs native/rux-vm/src/computer/handle.rs native/rux-vm/tests/rux_computer.rs
git commit -m "test(vm): cover Rux16 BIOS storage read"
```
