# Rux16 MMIO Firmware Smoke Implementation Plan

> Issue: [#69](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/69)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove that a Rux16 program booted from guest RAM can interact with computer MMIO devices.

**Architecture:** Reuse the explicit Rux16 boot handoff path. Add an integration test that writes a small Rux16 firmware program into guest RAM; the program uses `const32` and `store32` to write `RUX` into debug MMIO and write a marker into control MMIO. Keep this as raw instruction words plus focused test helpers, not a full assembler.

**Tech Stack:** Rust 2021, `RuxComputerHandle`, `Rux16Cpu`, `ComputerMachine` MMIO constants, `cargo test`.

---

## File Structure

- Create `docs/superpowers/plans/2026-05-25/2026-05-25-issue-69-rux16-mmio-firmware-smoke.md`: this plan.
- Modify `native/rux-vm/tests/rux_computer.rs`: add the Rux16 MMIO firmware integration test and small Rux16 test helper encoders.

## Task 1: Docs

- [ ] **Step 1: Save this plan**

Use `apply_patch` to add this plan file.

- [ ] **Step 2: Verify docs whitespace**

Run: `git diff --check`

Expected: exit 0.

- [ ] **Step 3: Commit docs**

```bash
git add docs/superpowers/plans/2026-05-25/2026-05-25-issue-69-rux16-mmio-firmware-smoke.md
git commit -m "docs(vm): plan Rux16 MMIO firmware smoke"
```

## Task 2: Integration Test

- [ ] **Step 1: Add the Rux16 MMIO firmware test**

Add a test in `native/rux-vm/tests/rux_computer.rs`:

```rust
#[test]
fn rux_computer_handle_rux16_firmware_writes_debug_and_control_mmio() {
    let bios = halt_i32_image(1);
    let entry_pc = 4096;
    let program = rux16_words(&[
        rux16_const32(0, ComputerMachine::DEBUG_WRITE),
        rux16_const4(1, b'R'),
        rux16_store32(0, 1),
        rux16_const4(1, b'U'),
        rux16_store32(0, 1),
        rux16_const4(1, b'X'),
        rux16_store32(0, 1),
        rux16_const32(0, ComputerMachine::CONTROL_PANIC_CODE),
        rux16_const32(1, 0x16),
        rux16_store32(0, 1),
        rux16_halt(),
    ]);
    let mut handle =
        RuxComputerHandle::create(&bios, 64 * 1024, 1_000_000).expect("computer handle creates");
    handle.write_guest_ram_bytes(entry_pc, &program).unwrap();

    handle
        .boot_handoff_rux16_from_guest_ram(entry_pc, program.len() as u32, 128)
        .expect("boot handoff accepts in-RAM Rux16 firmware");

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
```

- [ ] **Step 2: Run the new test**

Run: `cargo test --test rux_computer --manifest-path native/rux-vm/Cargo.toml rux_computer_handle_rux16_firmware_writes_debug_and_control_mmio`

Expected: test passes if the existing Rux16 instruction set already supports the needed MMIO path; otherwise implement only the missing instruction/helper needed by the test.

## Task 3: Verification And Commit

- [ ] **Step 1: Format touched Rust tests**

Run: `rustfmt native/rux-vm/tests/rux_computer.rs`

- [ ] **Step 2: Run focused test suite**

Run: `cargo test --test rux_computer --manifest-path native/rux-vm/Cargo.toml`

- [ ] **Step 3: Run full native VM tests**

Run: `cargo test --manifest-path native/rux-vm/Cargo.toml`

- [ ] **Step 4: Check whitespace**

Run: `git diff --check`

- [ ] **Step 5: Commit**

```bash
git add native/rux-vm/tests/rux_computer.rs
git commit -m "test(vm): cover Rux16 firmware MMIO writes"
```

## Task 4: Roadmap Update

- [ ] **Step 1: Update #69**

Add this plan link and note that Rux16 firmware can now be verified through debug/control MMIO writes.

- [ ] **Step 2: Leave #69 open**

Keep #69 open until storage boot and exec consume the Rux16 substrate.
