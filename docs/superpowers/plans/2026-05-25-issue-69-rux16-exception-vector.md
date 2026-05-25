# Rux16 Exception Vector Implementation Plan

> Issue: [#69](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/69)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace terminal host-side Rux16 traps with strict guest exception-vector control transfer.

**Architecture:** Add Rux16 control/status registers for `trap_vector`, `trap_cause`, `trap_pc`, and `trap_value`. CPU exceptions jump to `trap_vector`; if it is unset, execution returns a hard unhandled-exception error. No fallback path continues execution without a configured handler.

**Tech Stack:** Rust 2021, existing `MachineBus`/`MemoryBus`, integration tests in `native/rux-vm/tests/rux16.rs`, `cargo test`.

---

## File Structure

- Modify `docs/superpowers/specs/2026-05-24-issue-69-rux16-guest-instruction-memory-cpu-design.md`: document strict exception-vector behavior.
- Create `docs/superpowers/plans/2026-05-25-issue-69-rux16-exception-vector.md`: this plan.
- Modify `native/rux-vm/tests/rux16.rs`: add tests for configured exception handlers and hard unhandled exceptions.
- Modify `native/rux-vm/src/rux16.rs`: add CSR state, CSR instructions, structured trap causes, and exception vector transfer.

## Encoding For This Slice

Use the system page (`op = 0x0`):

```text
0x0ab2  read_csr  ra, csr_b
0x0ab3  write_csr csr_a, rb
```

CSR ids:

```text
1  trap_vector
2  trap_cause
3  trap_pc
4  trap_value
```

Only `trap_vector` is writable in this slice. Cause, pc, and value are CPU-owned and readable by guest handlers.

## Task 1: Docs

**Files:**
- Modify: `docs/superpowers/specs/2026-05-24-issue-69-rux16-guest-instruction-memory-cpu-design.md`
- Create: `docs/superpowers/plans/2026-05-25-issue-69-rux16-exception-vector.md`

- [ ] **Step 1: Save docs**

Use `apply_patch` to update the spec and add this plan.

- [ ] **Step 2: Verify docs whitespace**

Run: `git diff --check`

Expected: exit 0.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-05-24-issue-69-rux16-guest-instruction-memory-cpu-design.md docs/superpowers/plans/2026-05-25-issue-69-rux16-exception-vector.md
git commit -m "docs(vm): define Rux16 exception vector"
```

## Task 2: Failing Tests

**Files:**
- Modify: `native/rux-vm/tests/rux16.rs`

- [ ] **Step 1: Add tests**

Add tests for:

```rust
#[test]
fn rux16_illegal_instruction_enters_configured_exception_vector() {
    let mut bus = MachineBus::new(64).unwrap();
    write_words(
        &mut bus,
        0,
        &[
            const4(1, 8),
            write_csr(CSR_TRAP_VECTOR, 1),
            0xf123,
            halt(),
            read_csr(2, CSR_TRAP_CAUSE),
            read_csr(3, CSR_TRAP_PC),
            read_csr(4, CSR_TRAP_VALUE),
            halt(),
        ],
    );
    let mut cpu = Rux16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), Rux16Signal::Halt);
    assert_eq!(cpu.register(2), RUX16_TRAP_CAUSE_ILLEGAL_INSTRUCTION);
    assert_eq!(cpu.register(3), 4);
    assert_eq!(cpu.register(4), 0xf123);
}

#[test]
fn rux16_unhandled_exception_is_a_hard_error() {
    let mut bus = MachineBus::new(64).unwrap();
    write_words(&mut bus, 0, &[0xf123]);
    let mut cpu = Rux16Cpu::new(0);

    let error = cpu.run_until_signal(&mut bus, 16).unwrap_err();

    assert!(error.to_string().contains("unhandled exception"));
}
```

Add helpers `read_csr`, `write_csr`, and use exported CSR/cause constants.

- [ ] **Step 2: Run tests to verify RED**

Run: `cargo test --test rux16 --manifest-path native/rux-vm/Cargo.toml`

Expected: compile failure for missing CSR constants/instructions or test failures because illegal instructions still return the old trap signal.

## Task 3: Implementation

**Files:**
- Modify: `native/rux-vm/src/rux16.rs`
- Test: `native/rux-vm/tests/rux16.rs`

- [ ] **Step 1: Add public constants**

Expose CSR ids and cause codes:

```rust
pub const RUX16_CSR_TRAP_VECTOR: u32 = 1;
pub const RUX16_CSR_TRAP_CAUSE: u32 = 2;
pub const RUX16_CSR_TRAP_PC: u32 = 3;
pub const RUX16_CSR_TRAP_VALUE: u32 = 4;
pub const RUX16_TRAP_CAUSE_ILLEGAL_INSTRUCTION: u32 = 1;
```

- [ ] **Step 2: Add CSR state**

Add `trap_vector`, `trap_cause`, `trap_pc`, and `trap_value` to `Rux16Cpu`.

- [ ] **Step 3: Decode CSR instructions**

Decode `0x0ab2` as `ReadCsr { dst: a, csr: b }` and `0x0ab3` as `WriteCsr { csr: a, src: b }`.

- [ ] **Step 4: Raise exceptions through vector**

On decoder/execution errors, call `raise_exception(cause, fault_pc, value, message)`. If `trap_vector == 0`, return `Err(Rux16Trap)` with an unhandled-exception message. Otherwise record the trap CSRs and set `pc = trap_vector`.

- [ ] **Step 5: Execute CSR instructions**

`ReadCsr` reads the trap CSRs. `WriteCsr` writes only `trap_vector`; attempts to write other CSRs raise an explicit trap or return a hard error if no vector is configured.

- [ ] **Step 6: Run tests to verify GREEN**

Run: `cargo test --test rux16 --manifest-path native/rux-vm/Cargo.toml`

Expected: all Rux16 tests pass.

## Task 4: Verification And Commit

**Files:**
- `native/rux-vm/src/rux16.rs`
- `native/rux-vm/tests/rux16.rs`

- [ ] **Step 1: Format touched Rust files**

Run: `rustfmt native/rux-vm/src/rux16.rs native/rux-vm/tests/rux16.rs`

- [ ] **Step 2: Run full native VM tests**

Run: `cargo test --manifest-path native/rux-vm/Cargo.toml`

Expected: all native `rux-vm` tests pass.

- [ ] **Step 3: Check whitespace**

Run: `git diff --check`

Expected: exit 0.

- [ ] **Step 4: Commit**

```bash
git add native/rux-vm/src/rux16.rs native/rux-vm/tests/rux16.rs
git commit -m "feat(vm): add Rux16 exception vector"
```

## Task 5: Roadmap Update

**Files:**
- GitHub issue #69 only.

- [ ] **Step 1: Update issue body**

Add the plan link and current implementation status to #69:

```text
Plan: docs/superpowers/plans/2026-05-25-issue-69-rux16-exception-vector.md
```

- [ ] **Step 2: Leave issue open**

Keep #69 open. Exception vectors make traps more realistic, but boot/exec follow-up work still needs to consume this substrate.
