# Low VM Bare-Metal Computer Program Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the experimental `ComputerMachine` path into a bare-metal single-program computer MVP with ready/halt/panic status and deterministic debug/serial output.

**Architecture:** Keep Rust as the machine owner and keep the guest as one booted low VM image. `ComputerMachine` owns RAM, one boot CPU, control MMIO, and a new debug serial MMIO device. Old Rux OS process-table fixtures remain as research tests but are renamed/isolated so the active direction is clearly one bare-metal program, not guest processes.

**Tech Stack:** Rust `rux-vm`, `ComputerMachine`, `MachineBus`, `MmioDevice`, `LowCpuContext`, low VM `Instruction` fixtures, Cargo tests.

---

## File Structure

- Modify `native/rux-vm/src/computer_machine.rs`
  - Keep `ComputerMachine`, `ComputerControlDevice`, and tests together for this slice.
  - Add the bare-metal machine state constants and debug serial device.
  - Add public inspection APIs used by tests: debug output bytes/string and panic/fault state.
  - Add focused bare-metal tests near existing machine tests.
  - Rename old process-table/scheduler tests with a `legacy_ckl_os_research_` prefix.
- No Kotlin/JNI changes in this plan.
- No `dev` branch changes in this plan.

## Task 1: Make Machine Status Match Bare-Metal Semantics

**Files:**
- Modify: `native/rux-vm/src/computer_machine.rs`

- [ ] **Step 1: Write the failing status test**

Add this test near `computer_starts_in_reset_status`:

```rust
#[test]
fn bare_metal_program_halt_sets_machine_halted_status_and_exit_code() {
    let mut machine = ComputerMachine::new(1024).unwrap();
    let firmware = image(
        vec![
            Instruction::I32Const { dst: 0, value: 7 },
            Instruction::ReturnI32 { src: 0 },
        ],
        1,
    );

    let cpu_id = machine.spawn_boot_cpu(firmware, 128).unwrap();

    assert_eq!(
        machine.run_boot_cpu_until_signal(cpu_id).unwrap(),
        LowImageSignal::HaltI32(7),
    );
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
    assert_eq!(machine.exit_code(), 7);
    assert_eq!(machine.panic_code(), 0);
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml bare_metal_program_halt_sets_machine_halted_status_and_exit_code
```

Expected: FAIL to compile because `run_boot_cpu_until_signal`, `STATUS_HALTED`, and `exit_code` do not exist.

- [ ] **Step 3: Expand the control device ABI minimally**

In `ComputerMachine`, replace the status constants block with this expanded block:

```rust
pub const CONTROL_BASE: u32 = 0x1000_0000;
pub const CONTROL_STATUS: u32 = Self::CONTROL_BASE;
pub const CONTROL_PANIC_CODE: u32 = Self::CONTROL_BASE + 4;
pub const CONTROL_EXIT_CODE: u32 = Self::CONTROL_BASE + 8;
pub const STATUS_RESET: i32 = 0;
pub const STATUS_BOOTING: i32 = 1;
pub const STATUS_READY: i32 = 2;
pub const STATUS_HALTED: i32 = 3;
pub const STATUS_PANIC: i32 = 4;
```

Change `ComputerControlDevice` from:

```rust
struct ComputerControlDevice {
    status: i32,
    panic_code: i32,
}
```

to:

```rust
struct ComputerControlDevice {
    status: i32,
    panic_code: i32,
    exit_code: i32,
}
```

Change `ComputerControlDevice::new()` to:

```rust
fn new() -> Self {
    Self {
        status: ComputerMachine::STATUS_RESET,
        panic_code: 0,
        exit_code: 0,
    }
}
```

Change `ComputerControlDevice::SIZE` to:

```rust
const SIZE: u32 = 12;
```

Change `register_for_offset()` to:

```rust
fn register_for_offset(&mut self, offset: u32) -> Result<&mut i32, MemoryFault> {
    match offset {
        0 => Ok(&mut self.status),
        4 => Ok(&mut self.panic_code),
        8 => Ok(&mut self.exit_code),
        _ => Err(MemoryFault::new(format!(
            "computer control offset {offset} is not mapped",
        ))),
    }
}
```

Change `value_for_offset()` to:

```rust
fn value_for_offset(&self, offset: u32) -> Result<i32, MemoryFault> {
    match offset {
        0 => Ok(self.status),
        4 => Ok(self.panic_code),
        8 => Ok(self.exit_code),
        _ => Err(MemoryFault::new(format!(
            "computer control offset {offset} is not mapped",
        ))),
    }
}
```

- [ ] **Step 4: Add `exit_code` and boot-run API**

Add this public method beside `panic_code()`:

```rust
pub fn exit_code(&self) -> i32 {
    self.control_device().exit_code
}
```

Add this method beside `run_cpu_until_signal()`:

```rust
pub fn run_boot_cpu_until_signal(&mut self, cpu_id: CpuId) -> Result<LowImageSignal, String> {
    if self.boot_cpu != Some(cpu_id) {
        return Err(format!("CPU {cpu_id} is not the boot CPU"));
    }
    let signal = self.run_cpu_until_signal(cpu_id);
    match &signal {
        Ok(LowImageSignal::HaltUnit) => {
            self.set_halted_exit_code(0)?;
        }
        Ok(LowImageSignal::HaltI32(exit_code)) => {
            self.set_halted_exit_code(*exit_code)?;
        }
        Ok(LowImageSignal::HaltI64(exit_code)) => {
            self.set_halted_exit_code((*exit_code).clamp(i64::from(i32::MIN), i64::from(i32::MAX)) as i32)?;
        }
        Ok(LowImageSignal::HaltAddr(exit_code)) => {
            self.set_halted_exit_code(i32::from_ne_bytes(exit_code.to_ne_bytes()))?;
        }
        Ok(LowImageSignal::HaltBool(success)) => {
            self.set_halted_exit_code(if *success { 0 } else { 1 })?;
        }
        Err(message) => {
            self.set_panic_from_fault(message)?;
        }
        Ok(LowImageSignal::Pause) => {}
    }
    signal
}
```

Add these private helpers near `control_device()`:

```rust
fn control_device_mut(&mut self) -> &mut ComputerControlDevice {
    self.bus
        .device_mut::<ComputerControlDevice>(self.control_device_id)
        .expect("computer control device must be mapped")
}

fn set_halted_exit_code(&mut self, exit_code: i32) -> Result<(), String> {
    let control = self.control_device_mut();
    control.status = Self::STATUS_HALTED;
    control.exit_code = exit_code;
    Ok(())
}

fn set_panic_from_fault(&mut self, message: &str) -> Result<(), String> {
    let control = self.control_device_mut();
    control.status = Self::STATUS_PANIC;
    control.panic_code = stable_panic_code(message);
    Err(message.to_string())
}
```

Add this free function below the `impl ComputerMachine` block:

```rust
fn stable_panic_code(message: &str) -> i32 {
    message
        .bytes()
        .fold(0_i32, |hash, byte| hash.wrapping_mul(31).wrapping_add(i32::from(byte)))
}
```

- [ ] **Step 5: Update the memory map control size expectation**

In `computer_memory_map_describes_control_mmio_region`, change:

```rust
assert_eq!(control.size, 8);
```

to:

```rust
assert_eq!(control.size, 12);
```

- [ ] **Step 6: Run tests and verify GREEN**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml bare_metal_program_halt_sets_machine_halted_status_and_exit_code computer_memory_map_describes_control_mmio_region
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add native/rux-vm/src/computer_machine.rs
git commit -m "Add bare-metal computer halt status"
```

## Task 2: Add Debug Serial MMIO Device

**Files:**
- Modify: `native/rux-vm/src/computer_machine.rs`

- [ ] **Step 1: Write the failing debug output test**

Add this test near the new bare-metal halt test:

```rust
#[test]
fn bare_metal_program_writes_debug_serial_output() {
    let mut machine = ComputerMachine::new(1024).unwrap();
    let firmware = image(
        vec![
            Instruction::AddrConst {
                dst: 0,
                value: ComputerMachine::DEBUG_WRITE,
            },
            Instruction::I32Const { dst: 1, value: i32::from(b'H') },
            Instruction::Store32 { addr: 0, src: 1 },
            Instruction::I32Const { dst: 2, value: i32::from(b'I') },
            Instruction::Store32 { addr: 0, src: 2 },
            Instruction::ReturnUnit,
        ],
        3,
    );

    let cpu_id = machine.spawn_boot_cpu(firmware, 128).unwrap();

    assert_eq!(
        machine.run_boot_cpu_until_signal(cpu_id).unwrap(),
        LowImageSignal::HaltUnit,
    );
    assert_eq!(machine.debug_output_bytes(), b"HI");
    assert_eq!(machine.debug_output_string(), "HI");
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml bare_metal_program_writes_debug_serial_output
```

Expected: FAIL to compile because `DEBUG_WRITE`, `debug_output_bytes`, and `debug_output_string` do not exist.

- [ ] **Step 3: Add debug MMIO fields and mapping**

Change `ComputerMachine` fields from:

```rust
bus: MachineBus,
control_device_id: MmioDeviceId,
cpus: Vec<LowCpuContext>,
boot_cpu: Option<CpuId>,
```

to:

```rust
bus: MachineBus,
control_device_id: MmioDeviceId,
debug_device_id: MmioDeviceId,
cpus: Vec<LowCpuContext>,
boot_cpu: Option<CpuId>,
```

Add constants:

```rust
pub const DEBUG_BASE: u32 = 0x1000_0100;
pub const DEBUG_WRITE: u32 = Self::DEBUG_BASE;
```

Change `ComputerMachine::new()` to map the debug device after control:

```rust
let control_device_id =
    bus.map_mmio(Self::CONTROL_BASE, Box::new(ComputerControlDevice::new()))?;
let debug_device_id = bus.map_mmio(Self::DEBUG_BASE, Box::new(DebugSerialDevice::new()))?;
Ok(Self {
    bus,
    control_device_id,
    debug_device_id,
    cpus: Vec::new(),
    boot_cpu: None,
})
```

- [ ] **Step 4: Add debug output inspection APIs**

Add these public methods beside `panic_code()`/`exit_code()`:

```rust
pub fn debug_output_bytes(&self) -> &[u8] {
    self.debug_device().bytes()
}

pub fn debug_output_string(&self) -> String {
    String::from_utf8_lossy(self.debug_output_bytes()).into_owned()
}
```

Add this private helper beside `control_device()`:

```rust
fn debug_device(&self) -> &DebugSerialDevice {
    self.bus
        .device::<DebugSerialDevice>(self.debug_device_id)
        .expect("computer debug serial device must be mapped")
}
```

- [ ] **Step 5: Add `DebugSerialDevice`**

Add this struct and impl below `ComputerControlDevice`:

```rust
struct DebugSerialDevice {
    bytes: Vec<u8>,
}

impl DebugSerialDevice {
    const SIZE: u32 = 4;

    fn new() -> Self {
        Self { bytes: Vec::new() }
    }

    fn bytes(&self) -> &[u8] {
        &self.bytes
    }
}

impl MmioDevice for DebugSerialDevice {
    fn size(&self) -> u32 {
        Self::SIZE
    }

    fn load_i32(&self, offset: u32) -> Result<i32, MemoryFault> {
        if offset == 0 {
            Ok(0)
        } else {
            Err(MemoryFault::new(format!(
                "computer debug serial offset {offset} is not mapped",
            )))
        }
    }

    fn store_i32(&mut self, offset: u32, value: i32) -> Result<(), MemoryFault> {
        if offset != 0 {
            return Err(MemoryFault::new(format!(
                "computer debug serial offset {offset} is not mapped",
            )));
        }
        self.bytes.push(value.to_le_bytes()[0]);
        Ok(())
    }
}
```

- [ ] **Step 6: Add memory map region test**

Add this test near the other memory map tests:

```rust
#[test]
fn computer_memory_map_describes_debug_serial_mmio_region() {
    let machine = ComputerMachine::new(1024).unwrap();
    let map = machine.memory_map();
    let debug = map.region("debug").unwrap();

    assert_eq!(debug.base, ComputerMachine::DEBUG_BASE);
    assert_eq!(debug.size, 4);
    assert!(debug.readable);
    assert!(debug.writable);
}
```

Update `memory_map()` to include:

```rust
ComputerMemoryRegion {
    name: "debug",
    base: Self::DEBUG_BASE,
    size: DebugSerialDevice::SIZE,
    readable: true,
    writable: true,
},
```

- [ ] **Step 7: Run tests and verify GREEN**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml bare_metal_program_writes_debug_serial_output computer_memory_map_describes_debug_serial_mmio_region
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add native/rux-vm/src/computer_machine.rs
git commit -m "Add bare-metal debug serial MMIO"
```

## Task 3: Add Machine Fault/Panic Visibility

**Files:**
- Modify: `native/rux-vm/src/computer_machine.rs`

- [ ] **Step 1: Write the failing fault test**

Add this test near the bare-metal tests:

```rust
#[test]
fn bare_metal_program_fault_marks_machine_panicked() {
    let mut machine = ComputerMachine::new(1024).unwrap();
    let firmware = image(
        vec![
            Instruction::I32Const { dst: 0, value: 10 },
            Instruction::I32Const { dst: 1, value: 0 },
            Instruction::I32Div {
                dst: 2,
                lhs: 0,
                rhs: 1,
            },
            Instruction::ReturnUnit,
        ],
        3,
    );

    let cpu_id = machine.spawn_boot_cpu(firmware, 128).unwrap();
    let error = machine.run_boot_cpu_until_signal(cpu_id).unwrap_err();

    assert_eq!(error, "division by zero");
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_PANIC);
    assert_ne!(machine.panic_code(), 0);
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml bare_metal_program_fault_marks_machine_panicked
```

Expected before Task 1 implementation: compile failure. Expected after Task 1 implementation but before this task: if the helper already marks panic, this may PASS. If it passes, keep the test as coverage and continue to Step 4.

- [ ] **Step 3: Implement panic marking if needed**

If the test failed because `run_boot_cpu_until_signal` did not mark the machine panicked on `Err`, change the `Err(message)` branch to:

```rust
Err(message) => {
    self.set_panic_from_fault(message)?;
}
```

Keep `set_panic_from_fault()` as defined in Task 1.

- [ ] **Step 4: Run test and verify GREEN**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml bare_metal_program_fault_marks_machine_panicked
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add native/rux-vm/src/computer_machine.rs
git commit -m "Mark bare-metal machine panicked on VM fault"
```

If Step 2 already passed and no code changed, still commit the added test with the same message.

## Task 4: Reframe Old Rux OS Fixtures As Legacy Research

**Files:**
- Modify: `native/rux-vm/src/computer_machine.rs`

- [ ] **Step 1: Rename process-table constants comment**

Above the old OS constants:

```rust
const OS_STATE_BASE: u32 = 0x0001_0000;
```

add:

```rust
// Legacy Rux OS research fixtures. Keep these tests as reference material, but do not
// treat the guest process table/scheduler path as the current bare-metal MVP direction.
```

- [ ] **Step 2: Rename old process-oriented tests**

Rename these tests exactly:

```rust
fn boot_kernel_initializes_os_state_and_marks_machine_ready()
```

to:

```rust
fn legacy_ckl_os_research_boot_kernel_initializes_os_state_and_marks_machine_ready()
```

Rename:

```rust
fn boot_kernel_initializes_guest_process_table()
```

to:

```rust
fn legacy_ckl_os_research_boot_kernel_initializes_guest_process_table()
```

Rename:

```rust
fn scheduler_fixture_rotates_running_process_state()
```

to:

```rust
fn legacy_ckl_os_research_scheduler_fixture_rotates_running_process_state()
```

Rename:

```rust
fn kernel_launches_static_user_process_and_records_exit_code()
```

to:

```rust
fn legacy_ckl_os_research_kernel_launches_static_user_process_and_records_exit_code()
```

- [ ] **Step 3: Run renamed tests**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml legacy_ckl_os_research
```

Expected: PASS. The tests still exist, but their names now communicate that they are not the active MVP path.

- [ ] **Step 4: Commit**

```bash
git add native/rux-vm/src/computer_machine.rs
git commit -m "Mark Rux OS fixtures as legacy research"
```

## Task 5: Add Final Bare-Metal Smoke Test

**Files:**
- Modify: `native/rux-vm/src/computer_machine.rs`

- [ ] **Step 1: Write the final smoke test**

Add this test near the other bare-metal tests:

```rust
#[test]
fn bare_metal_firmware_marks_ready_writes_debug_and_halts() {
    let mut machine = ComputerMachine::new(1024).unwrap();
    let firmware = image(
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
                value: ComputerMachine::DEBUG_WRITE,
            },
            Instruction::I32Const { dst: 3, value: i32::from(b'O') },
            Instruction::Store32 { addr: 2, src: 3 },
            Instruction::I32Const { dst: 4, value: i32::from(b'K') },
            Instruction::Store32 { addr: 2, src: 4 },
            Instruction::I32Const {
                dst: 5,
                value: ComputerMachine::STATUS_READY,
            },
            Instruction::Store32 { addr: 0, src: 5 },
            Instruction::ReturnUnit,
        ],
        6,
    );

    let cpu_id = machine.spawn_boot_cpu(firmware, 128).unwrap();

    assert_eq!(
        machine.run_boot_cpu_until_signal(cpu_id).unwrap(),
        LowImageSignal::HaltUnit,
    );
    assert_eq!(machine.debug_output_string(), "OK");
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_HALTED);
    assert_eq!(machine.exit_code(), 0);
}
```

- [ ] **Step 2: Run smoke test**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml bare_metal_firmware_marks_ready_writes_debug_and_halts
```

Expected: PASS.

- [ ] **Step 3: Run full native test suite**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml
```

Expected: PASS.

- [ ] **Step 4: Format and check whitespace**

Run:

```bash
cargo fmt --manifest-path native/rux-vm/Cargo.toml --check
git diff --check
```

Expected: both commands exit successfully with no output.

- [ ] **Step 5: Commit**

```bash
git add native/rux-vm/src/computer_machine.rs
git commit -m "Add bare-metal computer firmware smoke test"
```

## Task 6: Update Research Note To Reflect The New Direction

**Files:**
- Modify: `docs/superpowers/todos/2026-05-12-low-vm-shared-ram-ckl-os-research-note.md`

- [ ] **Step 1: Append a 2026-05-13 update**

Append this section to the end of the research note:

```markdown
## 2026-05-13 Update: Bare-Metal Program Direction

The branch is active again, but with a narrower target than a full Rux OS.

The current direction is a bare-metal computer program:

- one `ComputerMachine`;
- one boot CPU;
- one low VM program;
- flat machine RAM;
- minimal control/debug MMIO;
- no guest process table in the MVP;
- no guest scheduler in the MVP;
- no virtual memory in the MVP.

The old process-table and scheduler fixtures remain useful research references, but they are not the active implementation path. The next useful milestone is a firmware-shaped program that boots, writes deterministic debug output, updates machine status, and halts with an exit code.
```

- [ ] **Step 2: Run markdown sanity check**

Run:

```bash
rg -n "Bare-Metal Program Direction|no guest process table|no guest scheduler" docs/superpowers/todos/2026-05-12-low-vm-shared-ram-ckl-os-research-note.md
```

Expected: all three phrases are found.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/todos/2026-05-12-low-vm-shared-ram-ckl-os-research-note.md
git commit -m "Document bare-metal experiment direction"
```

## Final Verification

- [ ] **Step 1: Run all native tests**

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml
```

Expected: PASS.

- [ ] **Step 2: Run formatting and whitespace checks**

```bash
cargo fmt --manifest-path native/rux-vm/Cargo.toml --check
git diff --check
```

Expected: PASS.

- [ ] **Step 3: Confirm clean worktree**

```bash
git status --short
```

Expected: no output.

## Notes For The Implementer

- Do not touch `dev` from this worktree.
- Do not add Kotlin/JNI integration in this plan.
- Do not delete legacy Rux OS research tests yet; renaming is enough.
- Do not add display, filesystem, terminal, or process APIs in this slice.
- Keep all observable guest output deterministic and testable from Rust.
