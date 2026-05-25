# Rux16 BIOS Flash Boot Implementation Plan

> Issue: [#71](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/71)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Boot a `ComputerMachine` directly from Rux16 BIOS flash bytes.

**Architecture:** Map BIOS flash as a read-only MMIO-like bus region at a documented reset vector. Start the boot CPU as `Rux16Cpu` at that vector, so instruction fetch reads BIOS bytes through the existing `MemoryBus` path. Do not decode a `LowImage` and do not provide a fallback path.

**Tech Stack:** Rust 2021, `MachineBus`, `MmioDevice`, `ComputerMachine`, `RuxComputerHandle`, native integration tests.

---

## File Structure

- Create `docs/superpowers/specs/2026-05-25-issue-71-rux16-bios-flash-boot-design.md`: design record.
- Create `docs/superpowers/plans/2026-05-25-issue-71-rux16-bios-flash-boot.md`: this plan.
- Modify `native/rux-vm/tests/rux_computer.rs`: add RED tests for direct Rux16 BIOS flash boot, empty BIOS flash rejection, and read-only flash writes.
- Modify `native/rux-vm/src/computer/devices.rs`: add a read-only BIOS flash device.
- Modify `native/rux-vm/src/computer/machine.rs`: map BIOS flash and spawn a Rux16 boot CPU at the reset vector.
- Modify `native/rux-vm/src/computer/handle.rs`: expose the direct Rux16 BIOS flash constructor.

## Task 1: Documentation

- [ ] **Step 1: Save the design and plan**

Use `apply_patch` to add the spec and this plan.

- [ ] **Step 2: Verify docs whitespace**

Run: `git diff --check`

Expected: exit 0.

- [ ] **Step 3: Commit docs**

```bash
git add docs/superpowers/specs/2026-05-25-issue-71-rux16-bios-flash-boot-design.md docs/superpowers/plans/2026-05-25-issue-71-rux16-bios-flash-boot.md
git commit -m "docs(vm): plan Rux16 BIOS flash boot"
```

## Task 2: Failing Tests

- [ ] **Step 1: Add direct BIOS flash boot tests**

Add tests to `native/rux-vm/tests/rux_computer.rs`:

```rust
#[test]
fn rux_computer_handle_boots_rux16_directly_from_bios_flash() {
    let bios = rux16_words(&rux16_mmio_firmware_words());
    let mut handle =
        RuxComputerHandle::create_rux16_bios_flash(&bios, 64 * 1024, 128)
            .expect("Rux16 BIOS flash computer creates");

    assert_eq!(handle.run_rux16_until_signal().unwrap(), Rux16Signal::Halt);
    assert_eq!(handle.debug_output_bytes(), b"RUX");
    assert_eq!(
        handle.control(),
        RuxComputerControl {
            status: ComputerMachine::STATUS_HALTED,
            exit_code: 0,
            panic_code: 0x16,
        },
    );
}

#[test]
fn rux_computer_handle_rejects_empty_rux16_bios_flash() {
    let error = RuxComputerHandle::create_rux16_bios_flash(&[], 64 * 1024, 128)
        .expect_err("empty Rux16 BIOS flash is rejected");

    assert!(error.contains("Rux16 BIOS flash is empty"), "unexpected error: {error}");
}

#[test]
fn rux_computer_handle_rux16_bios_flash_is_read_only() {
    let mut words = Vec::new();
    words.extend(rux16_const32(0, ComputerMachine::RUX16_BIOS_FLASH_BASE));
    words.extend(rux16_const32(1, 0x1234));
    words.push(rux16_store32(0, 1));
    words.push(rux16_halt());
    let bios = rux16_words(&words);
    let mut handle =
        RuxComputerHandle::create_rux16_bios_flash(&bios, 64 * 1024, 128)
            .expect("Rux16 BIOS flash computer creates");

    let error = handle.run_rux16_until_signal().expect_err("flash write traps");

    assert!(error.contains("BIOS flash is read-only"), "unexpected error: {error}");
}
```

- [ ] **Step 2: Run focused tests to verify RED**

Run: `cargo test --test rux_computer --manifest-path native/rux-vm/Cargo.toml rux_computer_handle_boots_rux16_directly_from_bios_flash`

Expected: compile failure for missing `create_rux16_bios_flash` or missing `RUX16_BIOS_FLASH_BASE`.

## Task 3: Implementation

- [ ] **Step 1: Add BIOS flash device**

Add a small read-only device in `native/rux-vm/src/computer/devices.rs`. It stores `Vec<u8>`, returns bytes on `load_u8`, returns little-endian `i32` on `load_i32`, and returns a `MemoryFault` with `BIOS flash is read-only` on writes.

- [ ] **Step 2: Add direct Rux16 boot constructor**

In `ComputerMachine`, add `RUX16_BIOS_FLASH_BASE` and `from_rux16_bios_flash(...)`. The constructor validates non-empty BIOS bytes, maps the flash, writes normal profile boot info, and inserts `ComputerCpuContext::Rux16 { cpu: Rux16Cpu::new(RUX16_BIOS_FLASH_BASE), max_steps: max_steps.max(1) }` as boot CPU `0`.

- [ ] **Step 3: Expose handle constructor**

Add `RuxComputerHandle::create_rux16_bios_flash(...)` that calls the new machine constructor and stores the returned boot CPU id.

- [ ] **Step 4: Run focused tests to verify GREEN**

Run: `cargo test --test rux_computer --manifest-path native/rux-vm/Cargo.toml rux_computer_handle_boots_rux16_directly_from_bios_flash`

Expected: the direct BIOS flash boot test passes.

## Task 4: Verification And Commit

- [ ] **Step 1: Format touched Rust files**

Run: `rustfmt native/rux-vm/src/computer/devices.rs native/rux-vm/src/computer/machine.rs native/rux-vm/src/computer/handle.rs native/rux-vm/tests/rux_computer.rs`

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
git add native/rux-vm/src/computer/devices.rs native/rux-vm/src/computer/machine.rs native/rux-vm/src/computer/handle.rs native/rux-vm/tests/rux_computer.rs
git commit -m "feat(vm): boot Rux16 BIOS from flash"
```

## Task 5: Roadmap Update

- [ ] **Step 1: Update issue #71**

Add the spec and plan links, mark completed acceptance criteria, and leave the issue open only if follow-up work remains.
