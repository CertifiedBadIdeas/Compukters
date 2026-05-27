# Rux16 Stage2 Storage Boot Implementation Plan

> Issue: [#62](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/62)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove a full guest-side boot chain from BIOS flash to a Rux16 stage2 program loaded from storage0 into RAM.

**Architecture:** Use a raw one-block boot header in storage0 block 0 and a one-block Rux16 stage2 image in block 1. BIOS runs from flash, validates the header magic, reads block 1 into RAM at `load_addr`, and jumps to `entry_pc`. No LowImage, no host-side decode, no fallback.

**Tech Stack:** Rust 2021, raw Rux16 instruction helpers, `RuxComputerHandle`, `storage0` MMIO, native integration tests.

---

## File Structure

- Create `docs/superpowers/specs/2026-05-25/2026-05-25-issue-62-rux16-stage2-storage-boot-design.md`: design record.
- Create `docs/superpowers/plans/2026-05-25/2026-05-25-issue-62-rux16-stage2-storage-boot.md`: this plan.
- Modify `native/rux-vm/tests/rux_computer.rs`: add a Rux16 BIOS storage stage2 integration test and helper encoders.

## Task 1: Documentation

- [ ] **Step 1: Save the spec and plan**

Use `apply_patch` to add the files above.

- [ ] **Step 2: Verify docs whitespace**

Run: `git diff --check -- docs/superpowers/specs/2026-05-25/2026-05-25-issue-62-rux16-stage2-storage-boot-design.md docs/superpowers/plans/2026-05-25/2026-05-25-issue-62-rux16-stage2-storage-boot.md`

Expected: exit 0.

- [ ] **Step 3: Commit docs**

```bash
git add docs/superpowers/specs/2026-05-25/2026-05-25-issue-62-rux16-stage2-storage-boot-design.md docs/superpowers/plans/2026-05-25/2026-05-25-issue-62-rux16-stage2-storage-boot.md
git commit -m "docs(vm): plan Rux16 stage2 storage boot"
```

## Task 2: Failing Test

- [ ] **Step 1: Add the stage2 boot test**

Add a test to `native/rux-vm/tests/rux_computer.rs`:

```rust
#[test]
fn rux_computer_handle_rux16_bios_loads_stage2_from_storage_and_jumps_to_ram() {
    let entry_pc = 2048;
    let bios = rux16_words(&rux16_stage2_boot_bios_words());
    let stage2 = rux16_words(&rux16_stage2_program_words());
    let media = rux16_boot_media(entry_pc, entry_pc, 1, 1, &stage2);
    let mut handle = RuxComputerHandle::create_rux16_bios_flash_with_storage0_media(
        &bios,
        64 * 1024,
        512,
        media,
    )
    .expect("Rux16 BIOS flash computer creates with boot media");

    assert_eq!(handle.run_rux16_until_signal().unwrap(), Rux16Signal::Halt);
    assert_eq!(handle.debug_output_bytes(), b"S2");
    assert_eq!(
        handle.control(),
        RuxComputerControl {
            status: ComputerMachine::STATUS_HALTED,
            exit_code: 0,
            panic_code: 0x52,
        },
    );
}
```

- [ ] **Step 2: Add helper functions**

Add helpers for `rux16_eq`, `rux16_branch_if_zero`, `rux16_jump`, `rux16_stage2_boot_bios_words`, `rux16_stage2_program_words`, and `rux16_boot_media`. The BIOS helper should:

- read storage0 block 0 to RAM address `512`;
- load and compare magic `"RUXB"`;
- if invalid, write `0xBAD` to control panic code and halt;
- read stage2 from `start_lba` into `load_addr`;
- jump to `entry_pc`.

- [ ] **Step 3: Run focused test to verify RED**

Run: `cargo test --test rux_computer --manifest-path native/rux-vm/Cargo.toml rux_computer_handle_rux16_bios_loads_stage2_from_storage_and_jumps_to_ram`

Expected: if helper code is incomplete, the test fails or does not compile. If the existing Rux16/storage substrate already supports the flow, the focused test may pass without production code; in that case, keep the test as the implementation slice.

## Task 3: Verification And Commit

- [ ] **Step 1: Format the touched test file**

Run: `rustfmt native/rux-vm/tests/rux_computer.rs`

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
git add native/rux-vm/tests/rux_computer.rs
git commit -m "test(vm): cover Rux16 stage2 storage boot"
```
