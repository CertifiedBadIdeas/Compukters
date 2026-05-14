# Bare-Metal Computer ABI v0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an explicit Rust ABI v0 module for the experimental bare-metal `ComputerMachine` path.

**Architecture:** `computer_abi` becomes the source of truth for RAM/control/debug addresses, sizes, and status values. `ComputerMachine` keeps compatibility aliases but delegates values to `computer_abi`, and tests prove that the machine memory map and firmware fixtures use the ABI contract.

**Tech Stack:** Rust `rux-vm`, `ComputerMachine`, `MachineBus`, low VM `Instruction` fixtures, Cargo tests.

---

## File Structure

- Create: `native/rux-vm/src/computer_abi.rs`
  - Public constants for RAM base, control MMIO, debug MMIO, and status codes.
- Modify: `native/rux-vm/src/lib.rs`
  - Export `computer_abi`.
- Modify: `native/rux-vm/src/computer_machine.rs`
  - Alias `ComputerMachine` compatibility constants to `computer_abi`.
  - Use ABI-defined control/debug sizes.
  - Update tests to assert against `computer_abi`.
- Modify: `docs/superpowers/todos/2026-05-12-low-vm-shared-ram-ckl-os-research-note.md`
  - Record that ABI v0 is now the active narrow experiment.

## Task 1: Add Public ABI Constants

**Files:**
- Create: `native/rux-vm/src/computer_abi.rs`
- Modify: `native/rux-vm/src/lib.rs`
- Modify: `native/rux-vm/src/computer_machine.rs`

- [ ] **Step 1: Write the failing ABI constants test**

In `native/rux-vm/src/computer_machine.rs`, change the test imports from:

```rust
use crate::computer_machine::ComputerMachine;
```

to:

```rust
use crate::computer_abi;
use crate::computer_machine::ComputerMachine;
```

Add this test near `computer_starts_in_reset_status`:

```rust
#[test]
fn computer_machine_constants_match_bare_metal_abi_v0() {
    assert_eq!(ComputerMachine::CONTROL_BASE, computer_abi::CONTROL_BASE);
    assert_eq!(ComputerMachine::CONTROL_STATUS, computer_abi::CONTROL_STATUS);
    assert_eq!(
        ComputerMachine::CONTROL_PANIC_CODE,
        computer_abi::CONTROL_PANIC_CODE,
    );
    assert_eq!(ComputerMachine::CONTROL_EXIT_CODE, computer_abi::CONTROL_EXIT_CODE);
    assert_eq!(ComputerMachine::CONTROL_SIZE, computer_abi::CONTROL_SIZE);
    assert_eq!(ComputerMachine::DEBUG_BASE, computer_abi::DEBUG_BASE);
    assert_eq!(ComputerMachine::DEBUG_WRITE, computer_abi::DEBUG_WRITE);
    assert_eq!(ComputerMachine::DEBUG_SIZE, computer_abi::DEBUG_SIZE);
    assert_eq!(ComputerMachine::STATUS_RESET, computer_abi::STATUS_RESET);
    assert_eq!(ComputerMachine::STATUS_BOOTING, computer_abi::STATUS_BOOTING);
    assert_eq!(ComputerMachine::STATUS_READY, computer_abi::STATUS_READY);
    assert_eq!(ComputerMachine::STATUS_HALTED, computer_abi::STATUS_HALTED);
    assert_eq!(ComputerMachine::STATUS_PANIC, computer_abi::STATUS_PANIC);
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml computer_machine_constants_match_bare_metal_abi_v0
```

Expected: FAIL to compile because `crate::computer_abi`, `ComputerMachine::CONTROL_SIZE`, and `ComputerMachine::DEBUG_SIZE` do not exist.

- [ ] **Step 3: Create the ABI module**

Create `native/rux-vm/src/computer_abi.rs`:

```rust
pub const RAM_BASE: u32 = 0x0000_0000;

pub const CONTROL_BASE: u32 = 0x1000_0000;
pub const CONTROL_STATUS: u32 = CONTROL_BASE;
pub const CONTROL_PANIC_CODE: u32 = CONTROL_BASE + 4;
pub const CONTROL_EXIT_CODE: u32 = CONTROL_BASE + 8;
pub const CONTROL_SIZE: u32 = 12;

pub const DEBUG_BASE: u32 = 0x1000_0100;
pub const DEBUG_WRITE: u32 = DEBUG_BASE;
pub const DEBUG_SIZE: u32 = 4;

pub const STATUS_RESET: i32 = 0;
pub const STATUS_BOOTING: i32 = 1;
pub const STATUS_READY: i32 = 2;
pub const STATUS_HALTED: i32 = 3;
pub const STATUS_PANIC: i32 = 4;
```

- [ ] **Step 4: Export the ABI module**

In `native/rux-vm/src/lib.rs`, add this line near the top:

```rust
pub mod computer_abi;
```

- [ ] **Step 5: Alias `ComputerMachine` constants to the ABI**

In `native/rux-vm/src/computer_machine.rs`, add this import at the top:

```rust
use crate::computer_abi;
```

Replace the current `ComputerMachine` constants block with:

```rust
pub const CONTROL_BASE: u32 = computer_abi::CONTROL_BASE;
pub const CONTROL_STATUS: u32 = computer_abi::CONTROL_STATUS;
pub const CONTROL_PANIC_CODE: u32 = computer_abi::CONTROL_PANIC_CODE;
pub const CONTROL_EXIT_CODE: u32 = computer_abi::CONTROL_EXIT_CODE;
pub const CONTROL_SIZE: u32 = computer_abi::CONTROL_SIZE;
pub const DEBUG_BASE: u32 = computer_abi::DEBUG_BASE;
pub const DEBUG_WRITE: u32 = computer_abi::DEBUG_WRITE;
pub const DEBUG_SIZE: u32 = computer_abi::DEBUG_SIZE;
pub const STATUS_RESET: i32 = computer_abi::STATUS_RESET;
pub const STATUS_BOOTING: i32 = computer_abi::STATUS_BOOTING;
pub const STATUS_READY: i32 = computer_abi::STATUS_READY;
pub const STATUS_HALTED: i32 = computer_abi::STATUS_HALTED;
pub const STATUS_PANIC: i32 = computer_abi::STATUS_PANIC;
```

- [ ] **Step 6: Run the focused test and verify GREEN**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml computer_machine_constants_match_bare_metal_abi_v0
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add native/rux-vm/src/computer_abi.rs native/rux-vm/src/lib.rs native/rux-vm/src/computer_machine.rs
git commit -m "Add bare-metal computer ABI constants"
```

## Task 2: Make Machine Internals Use ABI Sizes

**Files:**
- Modify: `native/rux-vm/src/computer_machine.rs`

- [ ] **Step 1: Write the size-source characterization test**

In `native/rux-vm/src/computer_machine.rs`, add this test near `computer_machine_constants_match_bare_metal_abi_v0`:

```rust
#[test]
fn computer_mmio_device_sizes_match_bare_metal_abi_v0() {
    let control = ComputerControlDevice::new();
    let debug = DebugSerialDevice::new();

    assert_eq!(control.size(), computer_abi::CONTROL_SIZE);
    assert_eq!(debug.size(), computer_abi::DEBUG_SIZE);
}
```

- [ ] **Step 2: Run the focused test**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml computer_mmio_device_sizes_match_bare_metal_abi_v0
```

Expected: PASS. This test locks behavior before the refactor.

- [ ] **Step 3: Replace private duplicate sizes with ABI aliases**

Change `ComputerControlDevice::SIZE` from:

```rust
const SIZE: u32 = 12;
```

to:

```rust
const SIZE: u32 = computer_abi::CONTROL_SIZE;
```

Change `DebugSerialDevice::SIZE` from:

```rust
const SIZE: u32 = 4;
```

to:

```rust
const SIZE: u32 = computer_abi::DEBUG_SIZE;
```

- [ ] **Step 4: Run the focused test and memory map tests**

Run each command:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml computer_mmio_device_sizes_match_bare_metal_abi_v0
cargo test --manifest-path native/rux-vm/Cargo.toml computer_memory_map_describes_control_mmio_region
cargo test --manifest-path native/rux-vm/Cargo.toml computer_memory_map_describes_debug_serial_mmio_region
```

Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add native/rux-vm/src/computer_machine.rs
git commit -m "Use ABI sizes for computer MMIO devices"
```

## Task 3: Make Memory Map Tests Assert ABI Values

**Files:**
- Modify: `native/rux-vm/src/computer_machine.rs`

- [ ] **Step 1: Update RAM memory map test to use ABI base**

In `computer_memory_map_describes_ram_region`, change:

```rust
assert_eq!(ram.base, 0);
```

to:

```rust
assert_eq!(ram.base, computer_abi::RAM_BASE);
```

- [ ] **Step 2: Update control memory map test to use ABI size**

In `computer_memory_map_describes_control_mmio_region`, change:

```rust
assert_eq!(control.base, ComputerMachine::CONTROL_BASE);
assert_eq!(control.size, 12);
```

to:

```rust
assert_eq!(control.base, computer_abi::CONTROL_BASE);
assert_eq!(control.size, computer_abi::CONTROL_SIZE);
```

- [ ] **Step 3: Update debug memory map test to use ABI size**

In `computer_memory_map_describes_debug_serial_mmio_region`, change:

```rust
assert_eq!(debug.base, ComputerMachine::DEBUG_BASE);
assert_eq!(debug.size, 4);
```

to:

```rust
assert_eq!(debug.base, computer_abi::DEBUG_BASE);
assert_eq!(debug.size, computer_abi::DEBUG_SIZE);
```

- [ ] **Step 4: Run memory map tests**

Run each command:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml computer_memory_map_describes_ram_region
cargo test --manifest-path native/rux-vm/Cargo.toml computer_memory_map_describes_control_mmio_region
cargo test --manifest-path native/rux-vm/Cargo.toml computer_memory_map_describes_debug_serial_mmio_region
```

Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add native/rux-vm/src/computer_machine.rs
git commit -m "Assert computer memory map against ABI"
```

## Task 4: Make Firmware Smoke Test Use ABI Directly

**Files:**
- Modify: `native/rux-vm/src/computer_machine.rs`

- [ ] **Step 1: Replace compatibility constants in the smoke test**

In `bare_metal_firmware_marks_ready_writes_debug_and_halts`, replace:

```rust
value: ComputerMachine::CONTROL_STATUS,
```

with:

```rust
value: computer_abi::CONTROL_STATUS,
```

Replace:

```rust
value: ComputerMachine::DEBUG_WRITE,
```

with:

```rust
value: computer_abi::DEBUG_WRITE,
```

Replace:

```rust
value: ComputerMachine::STATUS_BOOTING,
```

with:

```rust
value: computer_abi::STATUS_BOOTING,
```

Replace:

```rust
value: ComputerMachine::STATUS_READY,
```

with:

```rust
value: computer_abi::STATUS_READY,
```

Replace:

```rust
assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
```

with:

```rust
assert_eq!(machine.control_status(), computer_abi::STATUS_HALTED);
```

- [ ] **Step 2: Run the smoke test**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml bare_metal_firmware_marks_ready_writes_debug_and_halts
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add native/rux-vm/src/computer_machine.rs
git commit -m "Use bare-metal ABI in firmware smoke test"
```

## Task 5: Document ABI v0 In The Research Note

**Files:**
- Modify: `docs/superpowers/todos/2026-05-12-low-vm-shared-ram-ckl-os-research-note.md`

- [ ] **Step 1: Add an ABI v0 note**

Append this section:

```markdown
## 2026-05-13 Update: Bare-Metal ABI v0

The active experiment now has a narrow ABI boundary instead of an implicit `ComputerMachine` contract.

ABI v0 defines:

- RAM base;
- control MMIO base, size, and status registers;
- debug serial MMIO base and write register;
- status values for reset, booting, ready, halted, and panic.

This is still not a Rux OS. The purpose is to make one bootable firmware program target a stable machine contract before any Rux compiler or runtime work is added on top.
```

- [ ] **Step 2: Verify the note**

Run:

```bash
rg -n "Bare-Metal ABI v0|stable machine contract|not a Rux OS" docs/superpowers/todos/2026-05-12-low-vm-shared-ram-ckl-os-research-note.md
```

Expected: all three phrases appear.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/todos/2026-05-12-low-vm-shared-ram-ckl-os-research-note.md
git commit -m "Document bare-metal ABI v0 direction"
```

## Task 6: Final Verification

**Files:**
- Verify only.

- [ ] **Step 1: Run the native Rust VM test suite**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml
```

Expected: PASS.

- [ ] **Step 2: Run formatting check**

Run:

```bash
cargo fmt --manifest-path native/rux-vm/Cargo.toml --check
```

Expected: PASS.

- [ ] **Step 3: Run whitespace check**

Run:

```bash
git diff --check
```

Expected: PASS.

- [ ] **Step 4: Verify worktree state**

Run:

```bash
git status --short
```

Expected: no output after all commits.
