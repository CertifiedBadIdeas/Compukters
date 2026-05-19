# Rux Computer Display MMIO Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a text-mode `display0` MMIO device to `ComputerMachine`, expose it through Rux stdlib, and prove it with a firmware example.

**Architecture:** Extend the Rux computer target profile, not the frozen `RUXI` image ABI. `ComputerMachine` maps a fourth MMIO device and writes it into profile v2 `HardwareTable`. Firmware discovers it through boot info and uses `std::display` helpers.

**Tech Stack:** Rust native VM (`native/rux-vm`), Rust Rux compiler/stdlib (`native/rux-compiler`), Rux firmware examples (`.rx`), Cargo tests.

---

### Task 1: Native `display0` Hardware Entry And MMIO Device

**Files:**
- Modify: `native/rux-vm/src/computer_abi.rs`
- Modify: `native/rux-vm/src/computer_machine.rs`
- Modify: `docs/abi/rux-computer-profile-v1.md`

- [ ] **Step 1: Write failing native tests**

Add tests to `native/rux-vm/src/computer_machine.rs`:

```rust
#[test]
fn computer_machine_writes_display0_hardware_entry() {
    let machine = ComputerMachine::new(1024).unwrap();

    assert_eq!(read_u32(machine.memory(), 0x18), 4);
    assert_hardware_entry(
        machine.memory(),
        64,
        computer_abi::COMPUTER_HARDWARE_ID_DISPLAY0,
        computer_abi::DISPLAY0_BASE,
        computer_abi::DISPLAY0_SIZE,
    );
}

#[test]
fn computer_display0_mmio_reports_dimensions() {
    let machine = ComputerMachine::new(1024).unwrap();

    assert_eq!(machine.bus.load_i32(ComputerMachine::DISPLAY0_COLUMNS).unwrap(), 80);
    assert_eq!(machine.bus.load_i32(ComputerMachine::DISPLAY0_ROWS).unwrap(), 25);
}

#[test]
fn computer_display0_put_byte_updates_snapshot_and_sequence() {
    let mut machine = ComputerMachine::new(1024).unwrap();

    machine.bus.store_i32(ComputerMachine::DISPLAY0_DATA, i32::from(b'R')).unwrap();
    machine.bus.store_i32(ComputerMachine::DISPLAY0_COMMAND, ComputerMachine::DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR).unwrap();

    let snapshot = machine.display0_snapshot().unwrap();
    assert_eq!(snapshot.columns, 80);
    assert_eq!(snapshot.rows, 25);
    assert_eq!(snapshot.cursor_x, 1);
    assert_eq!(snapshot.cursor_y, 0);
    assert_eq!(snapshot.sequence, 1);
    assert_eq!(snapshot.cells[0], b'R');
}

#[test]
fn computer_display0_clear_and_newline_are_deterministic() {
    let mut machine = ComputerMachine::new(1024).unwrap();

    machine.bus.store_i32(ComputerMachine::DISPLAY0_DATA, i32::from(b'A')).unwrap();
    machine.bus.store_i32(ComputerMachine::DISPLAY0_COMMAND, ComputerMachine::DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR).unwrap();
    machine.bus.store_i32(ComputerMachine::DISPLAY0_COMMAND, ComputerMachine::DISPLAY0_COMMAND_NEWLINE).unwrap();
    machine.bus.store_i32(ComputerMachine::DISPLAY0_DATA, i32::from(b'B')).unwrap();
    machine.bus.store_i32(ComputerMachine::DISPLAY0_COMMAND, ComputerMachine::DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR).unwrap();
    machine.bus.store_i32(ComputerMachine::DISPLAY0_COMMAND, ComputerMachine::DISPLAY0_COMMAND_CLEAR).unwrap();

    let snapshot = machine.display0_snapshot().unwrap();
    assert_eq!(snapshot.cursor_x, 0);
    assert_eq!(snapshot.cursor_y, 0);
    assert_eq!(snapshot.sequence, 4);
    assert!(snapshot.cells.iter().all(|cell| *cell == 0));
}
```

- [ ] **Step 2: Run tests to verify RED**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml computer_display0
```

Expected: FAIL because display constants, snapshot API, and device do not exist.

- [ ] **Step 3: Implement constants and device**

Add to `native/rux-vm/src/computer_abi.rs`:

```rust
pub const COMPUTER_HARDWARE_ID_DISPLAY0: u32 = 4;

pub const DISPLAY0_BASE: u32 = 0x1000_0300;
pub const DISPLAY0_COLUMNS: u32 = DISPLAY0_BASE;
pub const DISPLAY0_ROWS: u32 = DISPLAY0_BASE + 4;
pub const DISPLAY0_CURSOR_X: u32 = DISPLAY0_BASE + 8;
pub const DISPLAY0_CURSOR_Y: u32 = DISPLAY0_BASE + 12;
pub const DISPLAY0_COMMAND: u32 = DISPLAY0_BASE + 16;
pub const DISPLAY0_DATA: u32 = DISPLAY0_BASE + 20;
pub const DISPLAY0_SEQUENCE_LOW: u32 = DISPLAY0_BASE + 24;
pub const DISPLAY0_SEQUENCE_HIGH: u32 = DISPLAY0_BASE + 28;
pub const DISPLAY0_SIZE: u32 = PROFILE_V2_PAGE_SIZE;

pub const DISPLAY0_COMMAND_CLEAR: i32 = 1;
pub const DISPLAY0_COMMAND_PUT_BYTE_AT_CURSOR: i32 = 2;
pub const DISPLAY0_COMMAND_PUT_BYTE_AT_XY: i32 = 3;
pub const DISPLAY0_COMMAND_NEWLINE: i32 = 4;
```

In `native/rux-vm/src/computer_machine.rs`, add `display0_device_id`, map `TextDisplayDevice`, expose constants, write a fourth hardware entry, add memory map entry, and add:

```rust
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ComputerTextDisplaySnapshot {
    pub columns: u32,
    pub rows: u32,
    pub cursor_x: u32,
    pub cursor_y: u32,
    pub sequence: u64,
    pub cells: Vec<u8>,
}
```

Implement `TextDisplayDevice` with 80x25 cells, cursor, data register, command execution, and sequence counter.

- [ ] **Step 4: Run tests to verify GREEN**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml computer_display0
```

Expected: PASS.

- [ ] **Step 5: Update profile docs**

Update `docs/abi/rux-computer-profile-v1.md` hardware table and add a `Display0 MMIO` section matching the design register layout.

- [ ] **Step 6: Commit**

```bash
git add native/rux-vm/src/computer_abi.rs native/rux-vm/src/computer_machine.rs docs/abi/rux-computer-profile-v1.md
git commit -m "feat: add rux computer text display mmio"
```

### Task 2: Rux `std::display` And Firmware Example

**Files:**
- Modify: `native/rux-compiler/src/stdlib.rs`
- Create: `native/rux-compiler/stdlib/std/display.rx`
- Create: `native/rux-compiler/examples/firmware/display_hello.rx`
- Modify: `native/rux-compiler/tests/compiler_seed.rs`
- Modify: `native/rux-compiler/tests/rux_runner.rs`

- [ ] **Step 1: Write failing compiler and runner tests**

Add to `native/rux-compiler/tests/compiler_seed.rs`:

```rust
#[test]
fn compile_imports_std_display_write_bytes() {
    let image = compile(
        "use std::display::write_bytes;

        fn main() {
            write_bytes(b\"OK\", 2u32);
        }",
    )
    .unwrap();

    assert!(image.functions.iter().any(|function| function.name == "write_bytes"));
}

#[test]
fn compiled_seed_std_display_write_bytes_runs_on_computer_machine() {
    let image = compile(
        "use std::display::{clear, write_bytes};

        fn main() {
            clear();
            write_bytes(b\"RUX\", 3u32);
        }",
    )
    .unwrap();
    let mut machine = ComputerMachine::new(64 * 1024).unwrap();
    let cpu_id = machine.spawn_boot_cpu(image, 1_000_000).unwrap();

    assert_eq!(machine.run_boot_cpu_until_signal(cpu_id).unwrap(), LowImageSignal::HaltUnit);
    let snapshot = machine.display0_snapshot().unwrap();
    assert_eq!(&snapshot.cells[0..3], b"RUX");
}
```

Add to `native/rux-compiler/tests/rux_runner.rs`:

```rust
#[test]
fn example_display_hello_firmware_runs() {
    let source = include_str!("../examples/firmware/display_hello.rx");
    let report = run_source(source).unwrap();

    assert_eq!(report.exit_code, 0);
}
```

- [ ] **Step 2: Run tests to verify RED**

Run:

```bash
cargo test --manifest-path native/rux-compiler/Cargo.toml std_display
```

Expected: FAIL because `std::display` is not registered.

- [ ] **Step 3: Implement stdlib module and firmware**

Register module in `native/rux-compiler/src/stdlib.rs`:

```rust
"display" => Some(include_str!("../stdlib/std/display.rx")),
```

Create `native/rux-compiler/stdlib/std/display.rx` with standalone public functions:

```rx
pub fn base() -> u32 { /* scan hardware table for id 4 */ }
pub fn columns() -> u32 { /* load base + 0, return 0 if missing */ }
pub fn rows() -> u32 { /* load base + 4, return 0 if missing */ }
pub fn clear() { /* command 1 */ }
pub fn put_byte(byte: u8) { /* data then command 2 */ }
pub fn newline() { /* command 4 */ }
pub fn write_bytes(bytes: ptr<u8>, len: u32) { /* loop put_byte semantics inline */ }
```

Create `native/rux-compiler/examples/firmware/display_hello.rx`:

```rx
use std::computer::{set_booting, set_ready};
use std::display::{clear, write_bytes};

fn main() -> i32 {
    set_booting();
    clear();
    write_bytes(b"RUX DISPLAY READY", 17u32);
    set_ready();
    return 0;
}
```

- [ ] **Step 4: Run tests to verify GREEN**

Run:

```bash
cargo test --manifest-path native/rux-compiler/Cargo.toml std_display
cargo test --manifest-path native/rux-compiler/Cargo.toml example_display_hello_firmware_runs
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add native/rux-compiler/src/stdlib.rs native/rux-compiler/stdlib/std/display.rx native/rux-compiler/examples/firmware/display_hello.rx native/rux-compiler/tests/compiler_seed.rs native/rux-compiler/tests/rux_runner.rs
git commit -m "feat: add rux display stdlib"
```

### Task 3: Full Verification

**Files:**
- Verify only.

- [ ] **Step 1: Format**

Run:

```bash
cargo fmt --manifest-path native/rux-vm/Cargo.toml
cargo fmt --manifest-path native/rux-compiler/Cargo.toml
```

Expected: exit 0.

- [ ] **Step 2: Run full Rust tests**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml
cargo test --manifest-path native/rux-compiler/Cargo.toml
```

Expected: all tests pass.

- [ ] **Step 3: Check git diff**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; only intended files are modified or no changes after commits.

