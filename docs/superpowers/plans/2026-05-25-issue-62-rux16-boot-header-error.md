# Rux16 Boot Header Error Implementation Plan

> Issue: [#62](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/62)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Verify that corrupt Rux16 boot metadata fails closed in BIOS.

**Architecture:** Reuse the existing raw Rux16 BIOS program that validates `RUXB`. Add a test with a corrupt block-0 magic and a valid stage2 block. BIOS should halt before jumping, report `0xB` in control MMIO, and produce no stage2 debug output.

**Tech Stack:** Rust 2021, raw Rux16 instruction helpers, `RuxComputerHandle`, `storage0` MMIO, native integration tests.

---

## File Structure

- Create `docs/superpowers/specs/2026-05-25-issue-62-rux16-boot-header-error-design.md`: design record.
- Create `docs/superpowers/plans/2026-05-25-issue-62-rux16-boot-header-error.md`: this plan.
- Modify `native/rux-vm/tests/rux_computer.rs`: add the corrupt boot header integration test and a helper that rewrites media magic.

## Task 1: Documentation

- [ ] **Step 1: Save spec and plan**

Use `apply_patch` to add the files above.

- [ ] **Step 2: Verify docs whitespace**

Run: `git diff --check -- docs/superpowers/specs/2026-05-25-issue-62-rux16-boot-header-error-design.md docs/superpowers/plans/2026-05-25-issue-62-rux16-boot-header-error.md`

Expected: exit 0.

- [ ] **Step 3: Commit docs**

```bash
git add docs/superpowers/specs/2026-05-25-issue-62-rux16-boot-header-error-design.md docs/superpowers/plans/2026-05-25-issue-62-rux16-boot-header-error.md
git commit -m "docs(vm): plan Rux16 boot header errors"
```

## Task 2: Failing Test

- [ ] **Step 1: Add corrupt header test**

Add a test to `native/rux-vm/tests/rux_computer.rs`:

```rust
#[test]
fn rux_computer_handle_rux16_bios_rejects_corrupt_boot_header_magic() {
    let entry_pc = 2048;
    let bios = rux16_words(&rux16_stage2_boot_bios_words());
    let stage2 = rux16_words(&rux16_stage2_program_words());
    let mut media = rux16_boot_media(entry_pc, entry_pc, 1, 1, &stage2);
    media[0..4].copy_from_slice(b"NOPE");
    let mut handle = RuxComputerHandle::create_rux16_bios_flash_with_storage0_media(
        &bios,
        64 * 1024,
        512,
        media,
    )
    .expect("Rux16 BIOS flash computer creates with corrupt boot media");

    assert_eq!(handle.run_rux16_until_signal().unwrap(), Rux16Signal::Halt);
    assert_eq!(handle.debug_output_bytes(), b"");
    assert_eq!(
        handle.control(),
        RuxComputerControl {
            status: ComputerMachine::STATUS_HALTED,
            exit_code: 0,
            panic_code: 0xB,
        },
    );
}
```

- [ ] **Step 2: Run focused test**

Run: `cargo test --test rux_computer --manifest-path native/rux-vm/Cargo.toml rux_computer_handle_rux16_bios_rejects_corrupt_boot_header_magic`

Expected: test passes if existing BIOS helper already covers invalid magic. If it fails, adjust only the test BIOS helper to report `0xB`; do not add fallback behavior.

## Task 3: Verification And Commit

- [ ] **Step 1: Format test file**

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
git commit -m "test(vm): cover Rux16 boot header errors"
```
