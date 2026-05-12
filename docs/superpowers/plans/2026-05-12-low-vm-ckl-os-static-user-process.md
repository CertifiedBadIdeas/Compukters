# Low VM CKL OS Static User Process Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove that a CKL/low-image kernel can use a guest RAM process descriptor to launch a statically linked user function and record its exit status in the guest process table.

**Architecture:** Keep kernel and user code in one low-image module for this slice. The process table stores a function-index marker in the `entry` field; the kernel validates that descriptor, calls the static user function, stores the returned exit code, and marks the process as exited.

**Tech Stack:** Rust `ckl-vm`, low VM `Instruction::CallStatic`, `ComputerMachine`, guest RAM process table fixtures, Cargo tests.

---

## Task 1: Static User Process Launch Fixture

**Files:**
- Modify: `native/ckl-vm/src/computer_machine.rs`

- [ ] **Step 1: Write the failing test**

Add this test to the `computer_machine` test module:

```rust
#[test]
fn kernel_launches_static_user_process_and_records_exit_code() {
    let mut machine = ComputerMachine::new(0x0002_0000).unwrap();
    let kernel = Image {
        language_version: "ckl-low-1".to_string(),
        memory_size: 0x0002_0000,
        rodata: Vec::new(),
        data: Vec::new(),
        bss_size: 0,
        entry_function_index: 0,
        functions: vec![
            Function {
                name: "kernel".to_string(),
                register_count: 18,
                parameters: Vec::new(),
                instructions: vec![
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
                    Instruction::I32Const { dst: 7, value: 1 },
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
                    Instruction::I32Const {
                        dst: 11,
                        value: USER_PROCESS_FUNCTION_INDEX,
                    },
                    Instruction::Store32 { addr: 10, src: 11 },
                    Instruction::Load32 { dst: 12, addr: 10 },
                    Instruction::I32Eq {
                        dst: 13,
                        lhs: 12,
                        rhs: 11,
                    },
                    Instruction::JumpIfFalse {
                        cond: 13,
                        target: 29,
                    },
                    Instruction::CallStatic {
                        return_register: Some(14),
                        function_index: USER_PROCESS_FUNCTION_INDEX as usize,
                        arguments: Vec::new(),
                    },
                    Instruction::AddrConst {
                        dst: 15,
                        value: PROCESS_TABLE_BASE + PROCESS_EXIT_CODE_OFFSET,
                    },
                    Instruction::Store32 { addr: 15, src: 14 },
                    Instruction::I32Const {
                        dst: 16,
                        value: PROCESS_EXITED,
                    },
                    Instruction::Store32 { addr: 8, src: 16 },
                    Instruction::I32Const {
                        dst: 17,
                        value: ComputerMachine::STATUS_READY,
                    },
                    Instruction::Store32 { addr: 0, src: 17 },
                    Instruction::ReturnUnit,
                    Instruction::I32Const {
                        dst: 17,
                        value: ComputerMachine::STATUS_PANIC,
                    },
                    Instruction::Store32 { addr: 0, src: 17 },
                    Instruction::ReturnUnit,
                ],
            },
            Function {
                name: "user_main".to_string(),
                register_count: 1,
                parameters: Vec::new(),
                instructions: vec![
                    Instruction::I32Const { dst: 0, value: 42 },
                    Instruction::ReturnI32 { src: 0 },
                ],
            },
        ],
    };

    let cpu_id = machine.spawn_boot_cpu(kernel, 1024).unwrap();

    assert_eq!(
        machine.run_cpu_until_signal(cpu_id).unwrap(),
        LowImageSignal::HaltUnit,
    );
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_READY);
    assert_eq!(
        machine
            .memory()
            .load_i32(PROCESS_TABLE_BASE + PROCESS_STATE_OFFSET)
            .unwrap(),
        PROCESS_EXITED,
    );
    assert_eq!(
        machine
            .memory()
            .load_i32(PROCESS_TABLE_BASE + PROCESS_EXIT_CODE_OFFSET)
            .unwrap(),
        42,
    );
}
```

- [ ] **Step 2: Run the focused test**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml kernel_launches_static_user_process_and_records_exit_code
```

Expected before constants exist: compile failure for `USER_PROCESS_FUNCTION_INDEX`, `PROCESS_EXIT_CODE_OFFSET`, and `PROCESS_EXITED`.

- [ ] **Step 3: Add the missing constants**

Add these constants to the `computer_machine` test module near the existing process constants:

```rust
const PROCESS_EXIT_CODE_OFFSET: u32 = 12;
const PROCESS_EXITED: i32 = 3;
const USER_PROCESS_FUNCTION_INDEX: i32 = 1;
```

- [ ] **Step 4: Run the focused test again**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml kernel_launches_static_user_process_and_records_exit_code
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add native/ckl-vm/src/computer_machine.rs
git commit -m "Add CKL OS static user process fixture"
```

## Task 2: Final Verification

**Files:**
- No code changes expected.

- [ ] **Step 1: Format Rust**

Run:

```bash
cargo fmt --manifest-path native/ckl-vm/Cargo.toml
```

Expected: no semantic changes.

- [ ] **Step 2: Run Rust tests**

Run:

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml
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

Expected: no whitespace errors and clean status after commits.
