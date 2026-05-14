# Low VM Rux OS Process Table Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove that process-table and scheduler state can live in guest RAM and be owned by the Rux/low-image kernel rather than Rust.

**Architecture:** Keep Rust as the machine owner: shared RAM, CPU contexts, MMIO, and run API. Add low-image kernel fixtures that initialize an OS state block, create two process table entries, and perform one cooperative scheduler step entirely by reading and writing guest RAM.

**Tech Stack:** Rust `rux-vm`, low VM `Instruction` fixtures, `ComputerMachine`, `MachineMemory`, Cargo tests.

---

## Memory Layout

Use the existing test-only OS state constants in `native/rux-vm/src/computer_machine.rs`.

Add these constants inside the test module:

```rust
const OS_CURRENT_PID: u32 = OS_STATE_BASE + 4;
const OS_PROCESS_COUNT: u32 = OS_STATE_BASE + 8;
const PROCESS_TABLE_BASE: u32 = OS_STATE_BASE + 0x100;
const PROCESS_ENTRY_SIZE: u32 = 16;
const PROCESS_STATE_OFFSET: u32 = 0;
const PROCESS_ENTRY_OFFSET: u32 = 4;
const PROCESS_STACK_PTR_OFFSET: u32 = 8;
const PROCESS_EXIT_CODE_OFFSET: u32 = 12;
const PROCESS_EMPTY: i32 = 0;
const PROCESS_RUNNABLE: i32 = 1;
const PROCESS_RUNNING: i32 = 2;
const PROCESS_EXITED: i32 = 3;
```

## Task 1: Boot Fixture Initializes Guest Process Table

**Files:**
- Modify: `native/rux-vm/src/computer_machine.rs`

- [ ] **Step 1: Write the failing test**

Add this test to the `computer_machine` test module:

```rust
#[test]
fn boot_kernel_initializes_guest_process_table() {
    let mut machine = ComputerMachine::new(0x0002_0000).unwrap();
    let kernel = image(
        vec![
            Instruction::AddrConst {
                dst: 0,
                value: ComputerMachine::CONTROL_STATUS,
            },
            Instruction::I32Const {
                dst: 1,
                value: ComputerMachine::STATUS_BOOTING,
            },
            Instruction::Store32 { addr: 0, src: 1 },
            Instruction::AddrConst {
                dst: 2,
                value: OS_STATE_BASE,
            },
            Instruction::I32Const {
                dst: 3,
                value: OS_MAGIC,
            },
            Instruction::Store32 { addr: 2, src: 3 },
            Instruction::AddrConst {
                dst: 4,
                value: OS_CURRENT_PID,
            },
            Instruction::I32Const { dst: 5, value: 0 },
            Instruction::Store32 { addr: 4, src: 5 },
            Instruction::AddrConst {
                dst: 6,
                value: OS_PROCESS_COUNT,
            },
            Instruction::I32Const { dst: 7, value: 2 },
            Instruction::Store32 { addr: 6, src: 7 },
            Instruction::AddrConst {
                dst: 8,
                value: PROCESS_TABLE_BASE + PROCESS_STATE_OFFSET,
            },
            Instruction::I32Const {
                dst: 9,
                value: PROCESS_RUNNING,
            },
            Instruction::Store32 { addr: 8, src: 9 },
            Instruction::AddrConst {
                dst: 10,
                value: PROCESS_TABLE_BASE + PROCESS_ENTRY_OFFSET,
            },
            Instruction::AddrConst {
                dst: 11,
                value: 0x0008_0000,
            },
            Instruction::Store32 { addr: 10, src: 11 },
            Instruction::AddrConst {
                dst: 12,
                value: PROCESS_TABLE_BASE + PROCESS_STACK_PTR_OFFSET,
            },
            Instruction::AddrConst {
                dst: 13,
                value: 0x0010_0000,
            },
            Instruction::Store32 { addr: 12, src: 13 },
            Instruction::AddrConst {
                dst: 14,
                value: PROCESS_TABLE_BASE + PROCESS_ENTRY_SIZE + PROCESS_STATE_OFFSET,
            },
            Instruction::I32Const {
                dst: 15,
                value: PROCESS_RUNNABLE,
            },
            Instruction::Store32 { addr: 14, src: 15 },
            Instruction::AddrConst {
                dst: 16,
                value: PROCESS_TABLE_BASE + PROCESS_ENTRY_SIZE + PROCESS_ENTRY_OFFSET,
            },
            Instruction::AddrConst {
                dst: 17,
                value: 0x0008_0100,
            },
            Instruction::Store32 { addr: 16, src: 17 },
            Instruction::AddrConst {
                dst: 18,
                value: PROCESS_TABLE_BASE + PROCESS_ENTRY_SIZE + PROCESS_STACK_PTR_OFFSET,
            },
            Instruction::AddrConst {
                dst: 19,
                value: 0x0010_1000,
            },
            Instruction::Store32 { addr: 18, src: 19 },
            Instruction::I32Const {
                dst: 20,
                value: ComputerMachine::STATUS_READY,
            },
            Instruction::Store32 { addr: 0, src: 20 },
            Instruction::ReturnUnit,
        ],
        21,
    );

    let cpu_id = machine.spawn_boot_cpu(kernel, 1024).unwrap();

    assert_eq!(
        machine.run_cpu_until_signal(cpu_id).unwrap(),
        LowImageSignal::HaltUnit,
    );
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_READY);
    assert_eq!(machine.memory().load_i32(OS_STATE_BASE).unwrap(), OS_MAGIC);
    assert_eq!(machine.memory().load_i32(OS_CURRENT_PID).unwrap(), 0);
    assert_eq!(machine.memory().load_i32(OS_PROCESS_COUNT).unwrap(), 2);
    assert_eq!(
        machine
            .memory()
            .load_i32(PROCESS_TABLE_BASE + PROCESS_STATE_OFFSET)
            .unwrap(),
        PROCESS_RUNNING,
    );
    assert_eq!(
        machine
            .memory()
            .load_i32(PROCESS_TABLE_BASE + PROCESS_ENTRY_SIZE + PROCESS_STATE_OFFSET)
            .unwrap(),
        PROCESS_RUNNABLE,
    );
}
```

- [ ] **Step 2: Run the focused test**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml boot_kernel_initializes_guest_process_table
```

Expected before constants exist: compile failure. Expected after constants are added: pass.

- [ ] **Step 3: Add the constants**

Add the constants from the Memory Layout section to the `computer_machine` test module.

- [ ] **Step 4: Run the focused test again**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml boot_kernel_initializes_guest_process_table
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add native/rux-vm/src/computer_machine.rs
git commit -m "Add Rux OS process table boot fixture"
```

## Task 2: Scheduler Fixture Rotates Running Process

**Files:**
- Modify: `native/rux-vm/src/computer_machine.rs`

- [ ] **Step 1: Write the failing test**

Add this test:

```rust
#[test]
fn scheduler_fixture_rotates_running_process_state() {
    let mut machine = ComputerMachine::new(0x0002_0000).unwrap();
    machine
        .memory_mut()
        .store_i32(OS_CURRENT_PID, 0)
        .unwrap();
    machine
        .memory_mut()
        .store_i32(PROCESS_TABLE_BASE + PROCESS_STATE_OFFSET, PROCESS_RUNNING)
        .unwrap();
    machine
        .memory_mut()
        .store_i32(
            PROCESS_TABLE_BASE + PROCESS_ENTRY_SIZE + PROCESS_STATE_OFFSET,
            PROCESS_RUNNABLE,
        )
        .unwrap();
    let scheduler = image(
        vec![
            Instruction::AddrConst {
                dst: 0,
                value: OS_CURRENT_PID,
            },
            Instruction::Load32 { dst: 1, addr: 0 },
            Instruction::I32Const { dst: 2, value: 0 },
            Instruction::I32Eq {
                dst: 3,
                lhs: 1,
                rhs: 2,
            },
            Instruction::JumpIfFalse {
                cond: 3,
                target: 20,
            },
            Instruction::AddrConst {
                dst: 4,
                value: PROCESS_TABLE_BASE + PROCESS_ENTRY_SIZE + PROCESS_STATE_OFFSET,
            },
            Instruction::Load32 { dst: 5, addr: 4 },
            Instruction::I32Const {
                dst: 6,
                value: PROCESS_RUNNABLE,
            },
            Instruction::I32Eq {
                dst: 7,
                lhs: 5,
                rhs: 6,
            },
            Instruction::JumpIfFalse {
                cond: 7,
                target: 20,
            },
            Instruction::AddrConst {
                dst: 8,
                value: PROCESS_TABLE_BASE + PROCESS_STATE_OFFSET,
            },
            Instruction::I32Const {
                dst: 9,
                value: PROCESS_RUNNABLE,
            },
            Instruction::Store32 { addr: 8, src: 9 },
            Instruction::I32Const {
                dst: 10,
                value: PROCESS_RUNNING,
            },
            Instruction::Store32 { addr: 4, src: 10 },
            Instruction::I32Const { dst: 11, value: 1 },
            Instruction::Store32 { addr: 0, src: 11 },
            Instruction::ReturnUnit,
            Instruction::ReturnUnit,
        ],
        12,
    );

    let cpu_id = machine.spawn_cpu(scheduler, 1024).unwrap();

    assert_eq!(
        machine.run_cpu_until_signal(cpu_id).unwrap(),
        LowImageSignal::HaltUnit,
    );
    assert_eq!(machine.memory().load_i32(OS_CURRENT_PID).unwrap(), 1);
    assert_eq!(
        machine
            .memory()
            .load_i32(PROCESS_TABLE_BASE + PROCESS_STATE_OFFSET)
            .unwrap(),
        PROCESS_RUNNABLE,
    );
    assert_eq!(
        machine
            .memory()
            .load_i32(PROCESS_TABLE_BASE + PROCESS_ENTRY_SIZE + PROCESS_STATE_OFFSET)
            .unwrap(),
        PROCESS_RUNNING,
    );
}
```

- [ ] **Step 2: Run focused scheduler test**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml scheduler_fixture_rotates_running_process_state
```

Expected: pass.

- [ ] **Step 3: Commit**

```bash
git add native/rux-vm/src/computer_machine.rs
git commit -m "Add Rux OS scheduler state fixture"
```

## Task 3: Final Verification

**Files:**
- No code changes expected.

- [ ] **Step 1: Format Rust**

Run:

```bash
cargo fmt --manifest-path native/rux-vm/Cargo.toml
```

Expected: no semantic changes.

- [ ] **Step 2: Run Rust tests**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml
```

Expected: all Rust tests pass.

- [ ] **Step 3: Run compiler tests**

Run:

```bash
./gradlew :compiler:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Check whitespace and status**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors. Status is clean after any formatting commit.

