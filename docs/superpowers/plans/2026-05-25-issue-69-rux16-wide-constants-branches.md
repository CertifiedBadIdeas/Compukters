# Rux16 Wide Constants And Branches Implementation Plan

> Issue: [#69](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/69)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the minimal Rux16 CPU with wide constants and relative conditional branches so small guest programs can use full `u32` values and control flow.

**Architecture:** Keep the existing `Rux16Decoder`/`DecodedInstruction` split. Add one extension-word instruction for `const32` and two branch instructions that resolve signed 4-bit relative offsets at decode time into semantic target PCs.

**Tech Stack:** Rust 2021, existing `MachineBus`/`MemoryBus`, integration tests in `native/rux-vm/tests/rux16.rs`, `cargo test`.

---

## File Structure

- Modify `native/rux-vm/tests/rux16.rs`: add integration tests for extension-word constants, branch-if-zero skipping, and branch-if-nonzero loop behavior.
- Modify `native/rux-vm/src/rux16.rs`: add `Const32`, `BranchIfZero`, and `BranchIfNonZero` semantic instructions and decode/execute support.

## Encoding For This Slice

Keep the base layout:

```text
bits 15..12  op
bits 11..8   a
bits 7..4    b
bits 3..0    c / subop / small immediate
```

Add these encodings:

```text
0xea01 imm_lo imm_hi    const32 ra, u32(imm_hi:imm_lo)
0x6a0i                 branch_if_zero ra, signed_nibble_offset_words
0x6a1i                 branch_if_nonzero ra, signed_nibble_offset_words
```

`const32` consumes two extension words after the instruction word. The immediate is little-endian at the word level: `imm_lo` is bits 15..0 and `imm_hi` is bits 31..16. Its `next_pc` is `pc + 6`.

Branches use a signed 4-bit offset measured in 16-bit instruction words from `next_pc`. For example, if a branch is at `pc = 0x000e`, its `next_pc` is `0x0010`; an offset of `-2` targets `0x000c`.

## Task 1: Plan Artifact

**Files:**
- Create: `docs/superpowers/plans/2026-05-25-issue-69-rux16-wide-constants-branches.md`

- [ ] **Step 1: Save this plan**

Use `apply_patch` to add this file.

- [ ] **Step 2: Verify plan formatting**

Run: `git diff --check`

Expected: exit 0.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/plans/2026-05-25-issue-69-rux16-wide-constants-branches.md
git commit -m "docs(vm): plan Rux16 wide constants and branches"
```

## Task 2: Failing Tests

**Files:**
- Modify: `native/rux-vm/tests/rux16.rs`

- [ ] **Step 1: Add tests**

Add tests that assert:

```rust
#[test]
fn rux16_const32_consumes_extension_words_and_loads_u32_value() {
    let mut bus = MachineBus::new(64).unwrap();
    let mut program = Vec::new();
    program.extend(const32(1, 0x1000_0040));
    program.push(halt());
    write_words(&mut bus, 0, &program);
    let mut cpu = Rux16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), Rux16Signal::Halt);
    assert_eq!(cpu.register(1), 0x1000_0040);
    assert_eq!(cpu.pc(), 8);
}

#[test]
fn rux16_branch_if_zero_skips_guest_instruction() {
    let mut bus = MachineBus::new(64).unwrap();
    write_words(
        &mut bus,
        0,
        &[branch_if_zero(1, 1), const4(2, 9), const4(2, 4), halt()],
    );
    let mut cpu = Rux16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), Rux16Signal::Halt);
    assert_eq!(cpu.register(2), 4);
}

#[test]
fn rux16_branch_if_nonzero_can_loop_with_negative_relative_offset() {
    let mut bus = MachineBus::new(64).unwrap();
    let mut program = vec![const4(1, 3), const4(2, 1), const4(3, 0)];
    program.extend(const32(4, u32::MAX));
    program.extend([add(3, 3, 2), add(1, 1, 4), branch_if_nonzero(1, -3), halt()]);
    write_words(&mut bus, 0, &program);
    let mut cpu = Rux16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 32).unwrap(), Rux16Signal::Halt);
    assert_eq!(cpu.register(1), 0);
    assert_eq!(cpu.register(3), 3);
}
```

Add helpers `const32`, `branch_if_zero`, `branch_if_nonzero`, and `encode_signed_nibble`.

- [ ] **Step 2: Run tests to verify RED**

Run: `cargo test --test rux16 --manifest-path native/rux-vm/Cargo.toml`

Expected: compile failure because the new decoder behavior is not implemented yet, or runtime failures because the encodings trap.

## Task 3: Decoder And Execution

**Files:**
- Modify: `native/rux-vm/src/rux16.rs`
- Test: `native/rux-vm/tests/rux16.rs`

- [ ] **Step 1: Add semantic instructions**

Extend `DecodedInstruction`:

```rust
Const32 { dst: usize, value: u32 },
BranchIfZero { src: usize, target_pc: u32 },
BranchIfNonZero { src: usize, target_pc: u32 },
```

- [ ] **Step 2: Decode extension-word const32**

In `Rux16Decoder`, decode `op == 0xe`, `b == 0`, `c == 1` as `Const32`. Read `pc + 2` and `pc + 4` with checked address arithmetic, combine the two `u16` words into a `u32`, and set `next_pc = pc + 6`.

- [ ] **Step 3: Decode relative conditional branches**

In `Rux16Decoder`, decode `op == 0x6`, `b == 0` as `BranchIfZero` and `b == 1` as `BranchIfNonZero`. Sign-extend the low nibble to `-8..7`, multiply by 2 bytes, and add it to `next_pc` with overflow checks.

- [ ] **Step 4: Execute new semantic instructions**

In `Rux16Cpu::step_with_decoder`:

```rust
DecodedInstruction::Const32 { dst, value } => self.registers[dst] = value,
DecodedInstruction::BranchIfZero { src, target_pc } => if self.registers[src] == 0 { self.pc = target_pc },
DecodedInstruction::BranchIfNonZero { src, target_pc } => if self.registers[src] != 0 { self.pc = target_pc },
```

- [ ] **Step 5: Run tests to verify GREEN**

Run: `cargo test --test rux16 --manifest-path native/rux-vm/Cargo.toml`

Expected: all `rux16` integration tests pass.

## Task 4: Regression Verification And Commit

**Files:**
- `native/rux-vm/src/rux16.rs`
- `native/rux-vm/tests/rux16.rs`

- [ ] **Step 1: Format touched Rust files**

Run: `rustfmt native/rux-vm/src/rux16.rs native/rux-vm/tests/rux16.rs`

- [ ] **Step 2: Run full native VM tests**

Run: `cargo test --manifest-path native/rux-vm/Cargo.toml`

Expected: all native `rux-vm` tests pass, including existing `LowImageVm` tests.

- [ ] **Step 3: Check whitespace**

Run: `git diff --check`

Expected: exit 0.

- [ ] **Step 4: Commit**

```bash
git add native/rux-vm/src/rux16.rs native/rux-vm/tests/rux16.rs
git commit -m "feat(vm): add Rux16 wide constants and branches"
```

## Task 5: Roadmap Update

**Files:**
- GitHub issue #69 only.

- [ ] **Step 1: Update issue body**

Add the implementation plan link and current implementation status to #69:

```text
Plan: docs/superpowers/plans/2026-05-25-issue-69-rux16-wide-constants-branches.md
```

- [ ] **Step 2: Leave issue open**

Keep #69 open after this slice. This commit adds wide constants and basic control flow, but the issue still has remaining acceptance criteria for byte loads/stores, call/ret or stack policy, and boot/exec migration.
