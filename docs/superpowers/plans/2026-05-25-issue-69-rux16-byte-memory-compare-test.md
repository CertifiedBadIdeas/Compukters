# Rux16 Byte Memory And Compare-Test Implementation Plan

> Issue: [#69](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/69)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend Rux16 with byte load/store and simple condition-building instructions so guest code can inspect byte-oriented headers and branch on equality or masked flags.

**Architecture:** Keep `Rux16Decoder` and `DecodedInstruction` as the boundary between binary encoding and execution. Reuse the existing load/store width nibble for `load8`/`store8`, and add compact compare/test instructions that read one extension word for the second operand or mask.

**Tech Stack:** Rust 2021, existing `MachineBus`/`MemoryBus`, integration tests in `native/rux-vm/tests/rux16.rs`, `cargo test`.

---

## File Structure

- Modify `native/rux-vm/tests/rux16.rs`: add integration tests for byte memory access, equality compare, and bit-mask testing.
- Modify `native/rux-vm/src/rux16.rs`: add `Load8`, `Store8`, `Eq`, and `TestBits` semantic instructions plus decoder/executor support.

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
0x4ab0       load8  ra, [rb]
0x5ab0       store8 [ra], rb
0x3a00 rhs   eq     ra, rb, rc   where rc is encoded by rhs low nibble
0x3a10 mask  test_bits ra, rb, mask16
```

`eq` consumes one extension word and uses its low nibble as the rhs register. This keeps the compare family extensible without stealing the `c` field for every compare form. It writes `1` when the two registers are equal and `0` otherwise.

`test_bits` consumes one extension word as a zero-extended 16-bit mask. It writes `1` when `(rb & mask) != 0` and `0` otherwise.

## Task 1: Plan Artifact

**Files:**
- Create: `docs/superpowers/plans/2026-05-25-issue-69-rux16-byte-memory-compare-test.md`

- [ ] **Step 1: Save this plan**

Use `apply_patch` to add this file.

- [ ] **Step 2: Verify plan formatting**

Run: `git diff --check`

Expected: exit 0.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/plans/2026-05-25-issue-69-rux16-byte-memory-compare-test.md
git commit -m "docs(vm): plan Rux16 byte memory and compare-test"
```

## Task 2: Failing Tests

**Files:**
- Modify: `native/rux-vm/tests/rux16.rs`

- [ ] **Step 1: Add tests**

Add tests for these behaviors:

```rust
#[test]
fn rux16_load8_and_store8_access_single_bytes_without_touching_neighbors() {
    let mut bus = MachineBus::new(64).unwrap();
    bus.store_u8(20, 0xaa).unwrap();
    bus.store_u8(21, 0xbb).unwrap();
    let mut program = vec![const4(1, 20), load8(2, 1), const4(3, 21), store8(3, 2), halt()];
    write_words(&mut bus, 0, &program);
    let mut cpu = Rux16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), Rux16Signal::Halt);
    assert_eq!(cpu.register(2), 0xaa);
    assert_eq!(bus.load_u8(20).unwrap(), 0xaa);
    assert_eq!(bus.load_u8(21).unwrap(), 0xaa);
    assert_eq!(bus.load_u8(22).unwrap(), 0);
}

#[test]
fn rux16_eq_builds_condition_register_for_branching() {
    let mut bus = MachineBus::new(64).unwrap();
    let mut program = vec![const4(1, 7), const4(2, 7)];
    program.extend(eq(3, 1, 2));
    program.extend([branch_if_zero(3, 1), const4(4, 5), halt()]);
    write_words(&mut bus, 0, &program);
    let mut cpu = Rux16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), Rux16Signal::Halt);
    assert_eq!(cpu.register(3), 1);
    assert_eq!(cpu.register(4), 5);
}

#[test]
fn rux16_test_bits_builds_condition_register_from_mask() {
    let mut bus = MachineBus::new(64).unwrap();
    let mut program = vec![const4(1, 0b1010)];
    program.extend(test_bits(2, 1, 0b1000));
    program.extend([branch_if_zero(2, 1), const4(3, 6), halt()]);
    write_words(&mut bus, 0, &program);
    let mut cpu = Rux16Cpu::new(0);

    assert_eq!(cpu.run_until_signal(&mut bus, 16).unwrap(), Rux16Signal::Halt);
    assert_eq!(cpu.register(2), 1);
    assert_eq!(cpu.register(3), 6);
}
```

Add helpers `load8`, `store8`, `eq`, and `test_bits`.

- [ ] **Step 2: Run tests to verify RED**

Run: `cargo test --test rux16 --manifest-path native/rux-vm/Cargo.toml`

Expected: new tests fail with `Trap` because the encodings are not implemented yet.

## Task 3: Decoder And Execution

**Files:**
- Modify: `native/rux-vm/src/rux16.rs`
- Test: `native/rux-vm/tests/rux16.rs`

- [ ] **Step 1: Add semantic instructions**

Extend `DecodedInstruction`:

```rust
Load8 { dst: usize, addr: usize },
Store8 { addr: usize, src: usize },
Eq { dst: usize, lhs: usize, rhs: usize },
TestBits { dst: usize, src: usize, mask: u32 },
```

- [ ] **Step 2: Decode byte memory ops**

In `Rux16Decoder`, decode `op == 0x4, c == 0` as `Load8`; decode `op == 0x5, c == 0` as `Store8`.

- [ ] **Step 3: Decode compare/test ops**

In `Rux16Decoder`, decode `op == 0x3, b == 0, c == 0` as `Eq` and consume one extension word. Decode `op == 0x3, b == 1, c == 0` as `TestBits` and consume one extension word. Both forms set `next_pc = pc + 4`.

- [ ] **Step 4: Execute new semantic instructions**

In `Rux16Cpu::step_with_decoder`, implement:

```rust
Load8 => registers[dst] = u32::from(bus.load_u8(registers[addr])?)
Store8 => bus.store_u8(registers[addr], registers[src] as u8)?
Eq => registers[dst] = u32::from(registers[lhs] == registers[rhs])
TestBits => registers[dst] = u32::from((registers[src] & mask) != 0)
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

Expected: all native `rux-vm` tests pass.

- [ ] **Step 3: Check whitespace**

Run: `git diff --check`

Expected: exit 0.

- [ ] **Step 4: Commit**

```bash
git add native/rux-vm/src/rux16.rs native/rux-vm/tests/rux16.rs
git commit -m "feat(vm): add Rux16 byte memory and compare-test"
```

## Task 5: Roadmap Update

**Files:**
- GitHub issue #69 only.

- [ ] **Step 1: Update issue body**

Add the implementation plan link and current implementation status to #69:

```text
Plan: docs/superpowers/plans/2026-05-25-issue-69-rux16-byte-memory-compare-test.md
```

- [ ] **Step 2: Leave issue open**

Keep #69 open after this slice. The issue still has remaining acceptance criteria for broader control flow, call/ret or stack policy, and boot/exec migration.
