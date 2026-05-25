# Rux16 Persistent Volume Boot Implementation Plan

> Issue: [#62](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/62)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Boot Rux16 stage2 from a path-backed `.ruxvol` through BIOS flash and `storage0` MMIO.

**Architecture:** Add a Rux16 BIOS flash handle constructor that accepts a `storage0` path and reuses the existing path-backed computer profile. Cover it with a native integration test that writes a temporary `.ruxvol`, boots the same raw header/stage2 media from BIOS flash, and observes stage2 output. This keeps host work limited to device wiring and initial Rux16 CPU start.

**Tech Stack:** Rust 2021, `RuxComputerHandle`, `ComputerMachineProfile`, Rux16 BIOS flash boot, path-backed `storage0`, native integration tests.

---

## File Structure

- Create `docs/superpowers/specs/2026-05-25-issue-62-rux16-persistent-volume-boot-design.md`: design record.
- Create `docs/superpowers/plans/2026-05-25-issue-62-rux16-persistent-volume-boot.md`: this plan.
- Modify `native/rux-vm/tests/rux_computer.rs`: add a failing path-backed `.ruxvol` stage2 boot test.
- Modify `native/rux-vm/src/computer/handle.rs`: add the public Rux16 BIOS flash constructor for path-backed `storage0`.

## Task 1: Documentation

- [ ] **Step 1: Save spec and plan**

Use `apply_patch` to add the files above.

- [ ] **Step 2: Verify docs whitespace**

Run: `git diff --check -- docs/superpowers/specs/2026-05-25-issue-62-rux16-persistent-volume-boot-design.md docs/superpowers/plans/2026-05-25-issue-62-rux16-persistent-volume-boot.md`

Expected: exit 0.

- [ ] **Step 3: Commit docs**

```bash
git add docs/superpowers/specs/2026-05-25-issue-62-rux16-persistent-volume-boot-design.md docs/superpowers/plans/2026-05-25-issue-62-rux16-persistent-volume-boot.md
git commit -m "docs(vm): plan Rux16 persistent volume boot"
```

## Task 2: Failing Test

- [ ] **Step 1: Add path-backed stage2 boot test**

Add this test near the existing Rux16 stage2 boot tests in `native/rux-vm/tests/rux_computer.rs`:

```rust
#[test]
fn rux_computer_handle_rux16_bios_loads_stage2_from_storage_volume_path() {
    let entry_pc = 2048;
    let bios = rux16_words(&rux16_stage2_boot_bios_words());
    let stage2 = rux16_words(&rux16_stage2_program_words());
    let media = rux16_boot_media(entry_pc, entry_pc, 1, 1, &stage2);
    let path = temp_volume_path("rux16-stage2-volume-path");
    write_rux_volume(&path, &media);
    let mut handle = RuxComputerHandle::create_rux16_bios_flash_with_storage0_path(
        &bios,
        64 * 1024,
        512,
        &path,
    )
    .expect("Rux16 BIOS flash computer creates with boot volume path");

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
    fs::remove_file(path).unwrap();
}
```

- [ ] **Step 2: Run focused test and verify RED**

Run: `cargo test --test rux_computer --manifest-path native/rux-vm/Cargo.toml rux_computer_handle_rux16_bios_loads_stage2_from_storage_volume_path`

Expected: compile failure because `RuxComputerHandle::create_rux16_bios_flash_with_storage0_path` does not exist yet.

## Task 3: Minimal Implementation

- [ ] **Step 1: Add the path-backed Rux16 BIOS flash constructor**

Add this method in `impl RuxComputerHandle` next to `create_rux16_bios_flash_with_storage0_media`:

```rust
pub fn create_rux16_bios_flash_with_storage0_path(
    bios_flash: &[u8],
    memory_size: usize,
    max_steps: u64,
    storage0_path: impl AsRef<Path>,
) -> Result<Self, String> {
    let profile = ComputerMachineProfile::computer_v1_with_storage0_path(
        memory_size,
        storage0_path,
    );
    let (machine, boot_cpu) =
        ComputerMachine::from_rux16_bios_flash_with_profile(bios_flash, profile, max_steps)?;
    Ok(Self { machine, boot_cpu })
}
```

- [ ] **Step 2: Run focused test and verify GREEN**

Run: `cargo test --test rux_computer --manifest-path native/rux-vm/Cargo.toml rux_computer_handle_rux16_bios_loads_stage2_from_storage_volume_path`

Expected: one test passes.

## Task 4: Verification And Commit

- [ ] **Step 1: Format changed Rust files**

Run: `rustfmt native/rux-vm/src/computer/handle.rs native/rux-vm/tests/rux_computer.rs`

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
git add native/rux-vm/src/computer/handle.rs native/rux-vm/tests/rux_computer.rs
git commit -m "feat(vm): boot Rux16 stage2 from volume path"
```
