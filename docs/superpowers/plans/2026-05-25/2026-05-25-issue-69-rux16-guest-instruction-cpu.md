# Rux16 Guest Instruction CPU Implementation Plan

> Issue: [#69](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/69)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the first minimal Rux16 CPU substrate that fetches 16-bit instruction words from guest memory, decodes them through a decoder boundary, and executes them against `MachineBus`.

**Architecture:** Add a parallel `native/rux-vm/src/rux16.rs` module and public `rux16` export without changing `LowImageVm`. The first instruction subset is intentionally small: `halt`, `const4`, `add`, `load32`, `store32`, and `jmp`, enough to prove guest-memory fetch/decode/execute, RAM access, MMIO access, and register-jump handoff shape.

**Tech Stack:** Rust 2021, existing `MachineBus`/`MemoryBus`, integration tests in `native/rux-vm/tests/rux16.rs`, `cargo test`.

---

## File Structure

- Create `native/rux-vm/tests/rux16.rs`: integration tests that define small Rux16 byte programs and assert CPU behavior through public APIs.
- Create `native/rux-vm/src/rux16.rs`: semantic instruction types, decoder trait, Rux16 decoder, CPU state, CPU executor, traps, and metrics.
- Modify `native/rux-vm/src/lib.rs`: export `pub mod rux16;`.

## Encoding For This Slice

All instruction words are little-endian and use the spec's base layout:

```text
bits 15..12  op
bits 11..8   a
bits 7..4    b
bits 3..0    c / subop / small immediate
```

The first concrete encodings are:

```text
0x0000                 nop
0x0001                 halt
0x1a0i                 const4 ra, i
0x2abc                 add ra, rb, rc
0x4ab2                 load32 ra, [rb]
0x5ab2                 store32 [ra], rb
0x7a00                 jmp ra
0xf000..0xffff         illegal trap
all other encodings    illegal trap
```

`const4` zero-extends its 4-bit immediate into the destination register. `add` wraps as `u32`. `load32` and `store32` use little-endian `i32` bus operations and preserve the underlying 32-bit bit pattern.

## Task 1: Plan Artifact

**Files:**
- Create: `docs/superpowers/plans/2026-05-25/2026-05-25-issue-69-rux16-guest-instruction-cpu.md`

- [ ] **Step 1: Save this plan**

Use `apply_patch` to add this file.

- [ ] **Step 2: Verify plan formatting**

Run: `git diff --check`

Expected: exit 0.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/plans/2026-05-25/2026-05-25-issue-69-rux16-guest-instruction-cpu.md
git commit -m "docs(vm): plan Rux16 guest CPU slice"
```

## Task 2: Failing Guest-Memory Execution Tests

**Files:**
- Create: `native/rux-vm/tests/rux16.rs`
- Modify: `native/rux-vm/src/lib.rs`

- [ ] **Step 1: Write failing tests**

Create `native/rux-vm/tests/rux16.rs` with tests for:

```rust
use rux_vm::low_bus::{MachineBus, MmioDevice};
use rux_vm::low_machine::MemoryFault;
use rux_vm::rux16::{Rux16Cpu, Rux16Signal};

#[test]
fn rux16_fetches_decodes_and_executes_words_from_guest_memory() {
    let mut bus = MachineBus::new(64).unwrap();
    write_words(&mut bus, 0, &[const4(1, 2), const4(2, 5), add(3, 1, 2), halt()]);
    let mut cpu = Rux16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), Rux16Signal::Halt);
    assert_eq!(cpu.register(3), 7);
    assert_eq!(cpu.pc(), 8);
}

#[test]
fn rux16_loads_and_stores_regular_ram_through_machine_bus() {
    let mut bus = MachineBus::new(64).unwrap();
    bus.store_i32(40, 0x0102_0304).unwrap();
    write_words(
        &mut bus,
        0,
        &[const4(1, 40), load32(2, 1), const4(3, 44), store32(3, 2), halt()],
    );
    let mut cpu = Rux16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), Rux16Signal::Halt);
    assert_eq!(cpu.register(2), 0x0102_0304);
    assert_eq!(bus.load_i32(44).unwrap(), 0x0102_0304);
}

#[test]
fn rux16_loads_and_stores_mmio_through_machine_bus() {
    let mut bus = MachineBus::new(64).unwrap();
    let device_id = bus
        .map_mmio(0x10, Box::new(RegisterDevice { value: 7 }))
        .unwrap();
    write_words(
        &mut bus,
        0,
        &[const4(1, 0x10), load32(2, 1), const4(3, 9), store32(1, 3), halt()],
    );
    let mut cpu = Rux16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), Rux16Signal::Halt);
    assert_eq!(cpu.register(2), 7);
    assert_eq!(bus.device::<RegisterDevice>(device_id).unwrap().value, 9);
}

#[test]
fn rux16_register_jump_sets_pc_to_guest_address() {
    let mut bus = MachineBus::new(64).unwrap();
    write_words(&mut bus, 0, &[const4(1, 6), jmp(1), const4(2, 1), halt()]);
    let mut cpu = Rux16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), Rux16Signal::Halt);
    assert_eq!(cpu.register(2), 0);
    assert_eq!(cpu.pc(), 8);
}

#[test]
fn rux16_illegal_instruction_reports_trap() {
    let mut bus = MachineBus::new(64).unwrap();
    write_words(&mut bus, 0, &[0xf000]);
    let mut cpu = Rux16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), Rux16Signal::Trap);
    assert!(cpu.trap().unwrap().contains("illegal instruction"));
}
```

Include local test helpers `write_words`, `const4`, `add`, `load32`, `store32`, `jmp`, `halt`, and a simple `RegisterDevice`.

- [ ] **Step 2: Run tests to verify RED**

Run: `cargo test --test rux16 --manifest-path native/rux-vm/Cargo.toml`

Expected: compile failure because `rux_vm::rux16` does not exist yet.

## Task 3: Minimal Rux16 CPU Module

**Files:**
- Create: `native/rux-vm/src/rux16.rs`
- Modify: `native/rux-vm/src/lib.rs`
- Test: `native/rux-vm/tests/rux16.rs`

- [ ] **Step 1: Implement public module export**

Add to `native/rux-vm/src/lib.rs`:

```rust
pub mod rux16;
```

- [ ] **Step 2: Implement decoder and CPU**

Create `native/rux-vm/src/rux16.rs` with:

```rust
pub trait InstructionDecoder {
    fn decode(&mut self, bus: &mut dyn MemoryBus, pc: u32) -> Result<DecodeResult, Rux16Trap>;
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DecodeResult {
    pub instruction: DecodedInstruction,
    pub next_pc: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DecodedInstruction {
    Nop,
    Halt,
    Const4 { dst: usize, value: u32 },
    Add { dst: usize, lhs: usize, rhs: usize },
    Load32 { dst: usize, addr: usize },
    Store32 { addr: usize, src: usize },
    Jump { target: usize },
}
```

Implement `Rux16Decoder` to fetch `bus.load_u16(pc)`, decode the encodings listed above, and produce `next_pc = pc + 2` unless the fetch or increment fails.

Implement `Rux16Cpu` with:

```rust
pub struct Rux16Cpu {
    pc: u32,
    registers: [u32; 16],
    state: Rux16State,
    metrics: Rux16Metrics,
}
```

Expose `new(entry_pc)`, `pc()`, `register(index)`, `metrics()`, `trap()`, `step(&mut self, &mut dyn MemoryBus)`, and `run_until_signal(&mut self, &mut dyn MemoryBus, max_steps)`.

- [ ] **Step 3: Run tests to verify GREEN**

Run: `cargo test --test rux16 --manifest-path native/rux-vm/Cargo.toml`

Expected: all `rux16` integration tests pass.

## Task 4: Regression Verification And Commit

**Files:**
- `native/rux-vm/src/rux16.rs`
- `native/rux-vm/src/lib.rs`
- `native/rux-vm/tests/rux16.rs`

- [ ] **Step 1: Run focused Rust VM tests**

Run: `cargo test --manifest-path native/rux-vm/Cargo.toml`

Expected: all native `rux-vm` tests pass, including existing `LowImageVm` tests.

- [ ] **Step 2: Check whitespace**

Run: `git diff --check`

Expected: exit 0.

- [ ] **Step 3: Commit**

```bash
git add native/rux-vm/src/lib.rs native/rux-vm/src/rux16.rs native/rux-vm/tests/rux16.rs
git commit -m "feat(vm): add minimal Rux16 guest CPU"
```

## Task 5: Roadmap Update

**Files:**
- GitHub issue #69 only.

- [ ] **Step 1: Update issue body**

Add the implementation plan link to `## Links` in #69:

```text
Plan: docs/superpowers/plans/2026-05-25/2026-05-25-issue-69-rux16-guest-instruction-cpu.md
```

- [ ] **Step 2: Leave issue open**

Keep #69 open after this slice. This commit satisfies the first executable substrate, but the issue still has remaining acceptance criteria for broader instruction coverage and boot/exec migration.
