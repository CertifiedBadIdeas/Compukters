# Rux16 Boot Handoff Implementation Plan

> Issue: [#69](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/69)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow the computer boot CPU to be replaced by a `Rux16Cpu` that starts executing instruction words already present in guest RAM.

**Architecture:** Keep the existing RUXI handoff as an explicit legacy path and add a separate Rux16 handoff path. `ComputerMachine` stores boot CPUs behind an internal enum so legacy `LowImage` CPUs and new `Rux16` CPUs cannot be confused. The Rux16 path validates the guest RAM range and starts at `entry_pc`; it does not host-decode bytes and does not fall back to RUXI.

**Tech Stack:** Rust 2021, `ComputerMachine`, `RuxComputerHandle`, `Rux16Cpu`, native integration tests in `native/rux-vm/tests/rux_computer.rs`, `cargo test`.

---

## File Structure

- Modify `docs/superpowers/plans/2026-05-25-issue-69-rux16-boot-handoff.md`: this plan.
- Modify `docs/superpowers/specs/2026-05-24-issue-69-rux16-guest-instruction-memory-cpu-design.md`: mark the boot-handoff slice as planned.
- Modify `native/rux-vm/tests/rux_computer.rs`: add a failing integration test for Rux16 handoff from guest RAM.
- Modify `native/rux-vm/src/computer/machine.rs`: store CPU contexts as an enum and add the Rux16 boot handoff/run path.
- Modify `native/rux-vm/src/computer/handle.rs`: expose explicit Rux16 handoff/run methods.

## Task 1: Docs

**Files:**
- Create: `docs/superpowers/plans/2026-05-25-issue-69-rux16-boot-handoff.md`
- Modify: `docs/superpowers/specs/2026-05-24-issue-69-rux16-guest-instruction-memory-cpu-design.md`

- [ ] **Step 1: Save docs**

Use `apply_patch` to add this plan and update the spec status.

- [ ] **Step 2: Verify docs whitespace**

Run: `git diff --check`

Expected: exit 0.

- [ ] **Step 3: Commit docs**

```bash
git add docs/superpowers/specs/2026-05-24-issue-69-rux16-guest-instruction-memory-cpu-design.md docs/superpowers/plans/2026-05-25-issue-69-rux16-boot-handoff.md
git commit -m "docs(vm): plan Rux16 boot handoff"
```

## Task 2: Failing Test

**Files:**
- Modify: `native/rux-vm/tests/rux_computer.rs`

- [ ] **Step 1: Add a Rux16 handoff test**

Add a test that writes raw Rux16 instruction words into guest RAM, calls `boot_handoff_rux16_from_guest_ram`, and runs the boot CPU through the Rux16-specific runner:

```rust
#[test]
fn rux_computer_handle_boot_handoff_starts_rux16_from_guest_ram_without_host_decode() {
    let bios = halt_i32_image(1);
    let entry_pc = 4096;
    let program = rux16_words(&[rux16_const4(1, 7), rux16_halt()]);
    let mut handle =
        RuxComputerHandle::create(&bios, 64 * 1024, 1_000_000).expect("computer handle creates");
    handle.write_guest_ram_bytes(entry_pc, &program).unwrap();

    let cpu_id = handle
        .boot_handoff_rux16_from_guest_ram(entry_pc, program.len() as u32, 128)
        .expect("boot handoff accepts in-RAM Rux16 program");

    assert_eq!(cpu_id, 0);
    assert_eq!(handle.run_rux16_until_signal().unwrap(), Rux16Signal::Halt);
}
```

- [ ] **Step 2: Run test to verify RED**

Run: `cargo test --test rux_computer --manifest-path native/rux-vm/Cargo.toml rux_computer_handle_boot_handoff_starts_rux16_from_guest_ram_without_host_decode`

Expected: compile failure for missing `RuxComputerHandle::boot_handoff_rux16_from_guest_ram`, missing `run_rux16_until_signal`, or missing test imports.

## Task 3: Implementation

**Files:**
- Modify: `native/rux-vm/src/computer/machine.rs`
- Modify: `native/rux-vm/src/computer/handle.rs`
- Test: `native/rux-vm/tests/rux_computer.rs`

- [ ] **Step 1: Add CPU context enum**

In `machine.rs`, replace `Vec<LowCpuContext>` with an internal enum:

```rust
enum ComputerCpuContext {
    LowImage(LowCpuContext),
    Rux16 { cpu: Rux16Cpu, max_steps: u64 },
}
```

Keep legacy `spawn_cpu`, `spawn_boot_cpu`, `run_cpu_until_signal`, and `run_boot_cpu_until_signal` operating only on `LowImage`.

- [ ] **Step 2: Add Rux16 handoff**

Add `ComputerMachine::boot_handoff_rux16_from_ram(entry_pc, byte_len, max_steps)`. It must:

- require an existing boot CPU;
- reject `byte_len == 0` with `BootHandoffError::EmptyImage`;
- validate `[entry_pc, entry_pc + byte_len)` against guest RAM;
- replace the existing boot CPU with `ComputerCpuContext::Rux16 { cpu: Rux16Cpu::new(entry_pc), max_steps: max_steps.max(1) }`;
- return the boot CPU id.

- [ ] **Step 3: Add Rux16 runner**

Add `ComputerMachine::run_boot_rux16_until_signal(cpu_id) -> Result<Rux16Signal, String>`. It must reject non-boot CPU ids and reject legacy LowImage CPUs with a clear error instead of attempting conversion.

- [ ] **Step 4: Expose handle methods**

Add `RuxComputerHandle::boot_handoff_rux16_from_guest_ram(...)` and `RuxComputerHandle::run_rux16_until_signal()`.

- [ ] **Step 5: Run test to verify GREEN**

Run: `cargo test --test rux_computer --manifest-path native/rux-vm/Cargo.toml rux_computer_handle_boot_handoff_starts_rux16_from_guest_ram_without_host_decode`

Expected: the new test passes.

## Task 4: Verification And Commit

**Files:**
- `native/rux-vm/src/computer/machine.rs`
- `native/rux-vm/src/computer/handle.rs`
- `native/rux-vm/tests/rux_computer.rs`

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
git commit -m "feat(vm): add Rux16 boot handoff"
```

## Task 5: Roadmap Update

**Files:**
- GitHub issue #69 only.

- [ ] **Step 1: Update issue body**

Add this plan link and status:

```text
Plan: docs/superpowers/plans/2026-05-25-issue-69-rux16-boot-handoff.md
Done in fifth implementation slice: explicit Rux16 boot handoff from guest RAM.
```

- [ ] **Step 2: Leave issue open**

Keep #69 open until the BIOS/storage boot and exec paths actually consume Rux16 end to end.
