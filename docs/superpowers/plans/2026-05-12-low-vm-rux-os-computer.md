# Low VM Rux OS Computer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first bootable Rux OS experiment slice where `ComputerMachine` owns shared RAM, MMIO, and boot CPU contexts while Rux/low-image fixtures initialize OS state through guest memory.

**Architecture:** Extract a reusable `LowCpuContext` from `LowImageCpu<'_>` so CPU execution state no longer borrows machine memory for its whole lifetime. Then make `ComputerMachine` own CPU contexts and run them against its shared `MachineBus`, proving the Rux OS direction with ready and panic boot fixtures.

**Tech Stack:** Rust `rux-vm`, low VM image model, `MachineMemory`, `MachineBus`, MMIO control registers, Rust unit/integration tests, Cargo.

---

## File Structure

- Modify `native/rux-vm/src/low_image_runner.rs`
  - Add public `LowCpuContext`.
  - Keep `LowImageVm` as the standalone owned-memory wrapper.
  - Keep `LowImageCpu<'memory>` as a compatibility wrapper around `LowCpuContext` for existing tests while the experiment matures.
- Modify `native/rux-vm/src/computer_machine.rs`
  - Add `CpuId`.
  - Store CPU contexts inside `ComputerMachine`.
  - Add boot/spawn/run APIs.
  - Update existing computer-machine tests to use owned CPU contexts.
- Modify `native/rux-vm/tests/low_image_runner.rs`
  - Add integration tests proving `LowCpuContext` can run against external shared memory without owning or borrowing it between runs.
- Run existing tests:
  - `cargo test --manifest-path native/rux-vm/Cargo.toml`
  - `./gradlew :compiler:test`

## Task 1: Extract `LowCpuContext`

**Files:**
- Modify: `native/rux-vm/src/low_image_runner.rs`
- Test: `native/rux-vm/tests/low_image_runner.rs`

- [ ] **Step 1: Write the failing test**

Add this test near the existing external-memory tests in `native/rux-vm/tests/low_image_runner.rs`:

```rust
#[test]
fn cpu_context_runs_against_shared_memory_without_owning_it() {
    let writer = image(
        vec![
            Instruction::AddrConst { dst: 0, value: 128 },
            Instruction::I32Const { dst: 1, value: 41 },
            Instruction::Store32 { addr: 0, src: 1 },
            Instruction::ReturnUnit,
        ],
        2,
    );
    let reader = image(
        vec![
            Instruction::AddrConst { dst: 0, value: 128 },
            Instruction::Load32 { dst: 1, addr: 0 },
            Instruction::I32Const { dst: 2, value: 1 },
            Instruction::I32Add {
                dst: 3,
                lhs: 1,
                rhs: 2,
            },
            Instruction::ReturnI32 { src: 3 },
        ],
        4,
    );
    let mut memory = MachineMemory::zeroed(1024).unwrap();
    let mut writer_cpu = LowImageVm::create_cpu_context(writer, 128).unwrap();
    let mut reader_cpu = LowImageVm::create_cpu_context(reader, 128).unwrap();

    assert_eq!(
        writer_cpu.run_until_signal(&mut memory).unwrap(),
        LowImageSignal::HaltUnit,
    );
    assert_eq!(
        reader_cpu.run_until_signal(&mut memory).unwrap(),
        LowImageSignal::HaltI32(42),
    );
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml cpu_context_runs_against_shared_memory_without_owning_it
```

Expected: compile failure because `LowImageVm::create_cpu_context` and `LowCpuContext::run_until_signal` do not exist.

- [ ] **Step 3: Add `LowCpuContext`**

In `native/rux-vm/src/low_image_runner.rs`, replace the current `LowImageCpu<'memory>` shape with a context-owned core plus compatibility wrapper:

```rust
pub struct LowImageVm {
    context: LowCpuContext,
    memory: MachineMemory,
}

pub struct LowCpuContext {
    program: LowProgram,
    state: LowState,
    slice_budget: Duration,
}

pub struct LowImageCpu<'memory> {
    context: LowCpuContext,
    memory: &'memory mut dyn MemoryBus,
}
```

Update `LowImageVm::create` to build a context:

```rust
pub fn create(image: Image, slice_budget_nanos: u64) -> Result<Self, String> {
    let memory_size = usize::try_from(image.memory_size)
        .map_err(|_| "memory size does not fit usize".to_string())?;
    let memory =
        MachineMemory::from_sections(memory_size, &image.rodata, &image.data, image.bss_size)
            .map_err(|error| error.to_string())?;
    let context = Self::create_cpu_context(image, slice_budget_nanos)?;
    Ok(Self { context, memory })
}
```

Add the new constructor:

```rust
pub fn create_cpu_context(
    image: Image,
    slice_budget_nanos: u64,
) -> Result<LowCpuContext, String> {
    let (program, state) = LowProgram::create(image)?;
    Ok(LowCpuContext {
        program,
        state,
        slice_budget: Duration::from_nanos(slice_budget_nanos.max(1)),
    })
}
```

Update `create_cpu_with_bus` to wrap the context:

```rust
pub fn create_cpu_with_bus<'memory>(
    image: Image,
    slice_budget_nanos: u64,
    memory: &'memory mut dyn MemoryBus,
) -> Result<LowImageCpu<'memory>, String> {
    let memory_size = usize::try_from(image.memory_size)
        .map_err(|_| "memory size does not fit usize".to_string())?;
    if memory.len() < memory_size {
        return Err(format!(
            "image requires {memory_size} bytes but machine memory has {} bytes",
            memory.len(),
        ));
    }
    Ok(LowImageCpu {
        context: Self::create_cpu_context(image, slice_budget_nanos)?,
        memory,
    })
}
```

Add `LowCpuContext` methods:

```rust
impl LowCpuContext {
    pub fn run_until_signal(
        &mut self,
        memory: &mut dyn MemoryBus,
    ) -> Result<LowImageSignal, String> {
        run_cpu_until_signal(
            &self.program,
            &mut self.state,
            memory,
            self.slice_budget,
        )
    }

    pub fn metrics_snapshot(&self) -> LowImageVmMetrics {
        self.state.metrics.clone()
    }
}
```

Update existing wrappers:

```rust
pub fn metrics_snapshot(&self) -> LowImageVmMetrics {
    self.context.metrics_snapshot()
}

pub fn run_until_signal(&mut self) -> Result<LowImageSignal, String> {
    self.context.run_until_signal(&mut self.memory)
}
```

```rust
impl LowImageCpu<'_> {
    pub fn run_until_signal(&mut self) -> Result<LowImageSignal, String> {
        self.context.run_until_signal(self.memory)
    }
}
```

- [ ] **Step 4: Run the focused test**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml cpu_context_runs_against_shared_memory_without_owning_it
```

Expected: pass.

- [ ] **Step 5: Run low image runner tests**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml --test low_image_runner
```

Expected: all tests in `low_image_runner` pass.

- [ ] **Step 6: Commit**

```bash
git add native/rux-vm/src/low_image_runner.rs native/rux-vm/tests/low_image_runner.rs
git commit -m "Extract low VM CPU context"
```

## Task 2: Make `ComputerMachine` Own CPU Contexts

**Files:**
- Modify: `native/rux-vm/src/computer_machine.rs`

- [ ] **Step 1: Write the failing test**

Add this test in `native/rux-vm/src/computer_machine.rs`:

```rust
#[test]
fn computer_machine_owns_boot_cpu_context() {
    let mut machine = ComputerMachine::new(1024).unwrap();
    let kernel = image(
        vec![
            Instruction::AddrConst {
                dst: 0,
                value: ComputerMachine::CONTROL_STATUS,
            },
            Instruction::I32Const {
                dst: 1,
                value: ComputerMachine::STATUS_READY,
            },
            Instruction::Store32 { addr: 0, src: 1 },
            Instruction::ReturnUnit,
        ],
        2,
    );

    let cpu_id = machine.spawn_boot_cpu(kernel, 128).unwrap();

    assert_eq!(machine.boot_cpu_id(), Some(cpu_id));
    assert_eq!(
        machine.run_cpu_until_signal(cpu_id).unwrap(),
        LowImageSignal::HaltUnit,
    );
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_READY);
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml computer_machine_owns_boot_cpu_context
```

Expected: compile failure because `spawn_boot_cpu`, `boot_cpu_id`, and `run_cpu_until_signal` do not exist.

- [ ] **Step 3: Implement CPU ownership API**

Modify the imports:

```rust
use crate::low_image_runner::{LowCpuContext, LowImageSignal, LowImageVm};
```

Add a public CPU id:

```rust
pub type CpuId = usize;
```

Change the `ComputerMachine` struct:

```rust
pub struct ComputerMachine {
    bus: MachineBus,
    control_device_id: MmioDeviceId,
    cpus: Vec<LowCpuContext>,
    boot_cpu: Option<CpuId>,
}
```

Initialize the new fields in `new`:

```rust
Ok(Self {
    bus,
    control_device_id,
    cpus: Vec::new(),
    boot_cpu: None,
})
```

Add these methods to `impl ComputerMachine`:

```rust
pub fn spawn_cpu(
    &mut self,
    image: Image,
    slice_budget_nanos: u64,
) -> Result<CpuId, String> {
    let required_memory = usize::try_from(image.memory_size)
        .map_err(|_| "memory size does not fit usize".to_string())?;
    if self.bus.len() < required_memory {
        return Err(format!(
            "image requires {required_memory} bytes but machine memory has {} bytes",
            self.bus.len(),
        ));
    }
    let cpu = LowImageVm::create_cpu_context(image, slice_budget_nanos)?;
    let cpu_id = self.cpus.len();
    self.cpus.push(cpu);
    Ok(cpu_id)
}

pub fn spawn_boot_cpu(
    &mut self,
    kernel_image: Image,
    slice_budget_nanos: u64,
) -> Result<CpuId, String> {
    if self.boot_cpu.is_some() {
        return Err("boot CPU is already spawned".to_string());
    }
    let cpu_id = self.spawn_cpu(kernel_image, slice_budget_nanos)?;
    self.boot_cpu = Some(cpu_id);
    Ok(cpu_id)
}

pub fn boot_cpu_id(&self) -> Option<CpuId> {
    self.boot_cpu
}

pub fn cpu_count(&self) -> usize {
    self.cpus.len()
}

pub fn run_cpu_until_signal(&mut self, cpu_id: CpuId) -> Result<LowImageSignal, String> {
    let cpu = self
        .cpus
        .get_mut(cpu_id)
        .ok_or_else(|| format!("CPU {cpu_id} is not present"))?;
    cpu.run_until_signal(&mut self.bus)
}
```

Remove or stop using the old `create_cpu`/`boot_cpu` APIs that returned `LowImageCpu<'_>` from `ComputerMachine`.

- [ ] **Step 4: Update existing `ComputerMachine` tests**

Replace test code shaped like this:

```rust
{
    let mut cpu = machine.create_cpu(writer, 128).unwrap();
    assert_eq!(cpu.run_until_signal().unwrap(), LowImageSignal::HaltUnit);
}
```

with:

```rust
let cpu_id = machine.spawn_cpu(writer, 128).unwrap();
assert_eq!(
    machine.run_cpu_until_signal(cpu_id).unwrap(),
    LowImageSignal::HaltUnit,
);
```

Replace boot code shaped like this:

```rust
let mut cpu = machine.boot_cpu(kernel, 128).unwrap();
assert_eq!(cpu.run_until_signal().unwrap(), LowImageSignal::HaltUnit);
```

with:

```rust
let cpu_id = machine.spawn_boot_cpu(kernel, 128).unwrap();
assert_eq!(
    machine.run_cpu_until_signal(cpu_id).unwrap(),
    LowImageSignal::HaltUnit,
);
```

- [ ] **Step 5: Run focused computer-machine tests**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml computer_machine
```

Expected: all `computer_machine` tests pass.

- [ ] **Step 6: Commit**

```bash
git add native/rux-vm/src/computer_machine.rs
git commit -m "Let computer machine own CPU contexts"
```

## Task 3: Add Ready Boot Kernel Fixture

**Files:**
- Modify: `native/rux-vm/src/computer_machine.rs`

- [ ] **Step 1: Write the failing test**

Add constants to the test module:

```rust
const OS_STATE_BASE: u32 = 0x0001_0000;
const OS_MAGIC: i32 = 0x434B_4F53;
const INITIAL_PROCESS_READY: i32 = 1;
```

Add this test:

```rust
#[test]
fn boot_kernel_initializes_os_state_and_marks_machine_ready() {
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
                value: OS_STATE_BASE + 4,
            },
            Instruction::I32Const {
                dst: 5,
                value: INITIAL_PROCESS_READY,
            },
            Instruction::Store32 { addr: 4, src: 5 },
            Instruction::I32Const {
                dst: 6,
                value: ComputerMachine::STATUS_READY,
            },
            Instruction::Store32 { addr: 0, src: 6 },
            Instruction::ReturnUnit,
        ],
        7,
    );

    let cpu_id = machine.spawn_boot_cpu(kernel, 512).unwrap();

    assert_eq!(
        machine.run_cpu_until_signal(cpu_id).unwrap(),
        LowImageSignal::HaltUnit,
    );
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_READY);
    assert_eq!(machine.memory().load_i32(OS_STATE_BASE).unwrap(), OS_MAGIC);
    assert_eq!(
        machine.memory().load_i32(OS_STATE_BASE + 4).unwrap(),
        INITIAL_PROCESS_READY,
    );
}
```

- [ ] **Step 2: Run the focused test**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml boot_kernel_initializes_os_state_and_marks_machine_ready
```

Expected: pass if Task 2 is complete. If it fails, fix only the machine CPU ownership path or the test fixture addresses.

- [ ] **Step 3: Commit**

```bash
git add native/rux-vm/src/computer_machine.rs
git commit -m "Add Rux OS ready boot fixture"
```

## Task 4: Add Panic Boot Kernel Fixture

**Files:**
- Modify: `native/rux-vm/src/computer_machine.rs`

- [ ] **Step 1: Write the failing test**

Add this test:

```rust
#[test]
fn boot_kernel_can_panic_through_control_mmio() {
    let mut machine = ComputerMachine::new(0x0002_0000).unwrap();
    let kernel = image(
        vec![
            Instruction::AddrConst {
                dst: 0,
                value: ComputerMachine::CONTROL_STATUS,
            },
            Instruction::AddrConst {
                dst: 1,
                value: ComputerMachine::CONTROL_PANIC_CODE,
            },
            Instruction::I32Const {
                dst: 2,
                value: ComputerMachine::STATUS_BOOTING,
            },
            Instruction::Store32 { addr: 0, src: 2 },
            Instruction::I32Const {
                dst: 3,
                value: 0x0BAD,
            },
            Instruction::Store32 { addr: 1, src: 3 },
            Instruction::I32Const {
                dst: 4,
                value: ComputerMachine::STATUS_PANIC,
            },
            Instruction::Store32 { addr: 0, src: 4 },
            Instruction::ReturnUnit,
        ],
        5,
    );

    let cpu_id = machine.spawn_boot_cpu(kernel, 512).unwrap();

    assert_eq!(
        machine.run_cpu_until_signal(cpu_id).unwrap(),
        LowImageSignal::HaltUnit,
    );
    assert_eq!(machine.control_status(), ComputerMachine::STATUS_PANIC);
    assert_eq!(machine.panic_code(), 0x0BAD);
}
```

- [ ] **Step 2: Run the focused test**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml boot_kernel_can_panic_through_control_mmio
```

Expected: pass.

- [ ] **Step 3: Commit**

```bash
git add native/rux-vm/src/computer_machine.rs
git commit -m "Add Rux OS panic boot fixture"
```

## Task 5: Add Guardrails For Boot CPU Ownership

**Files:**
- Modify: `native/rux-vm/src/computer_machine.rs`

- [ ] **Step 1: Write the failing tests**

Add these tests:

```rust
#[test]
fn computer_machine_rejects_second_boot_cpu() {
    let mut machine = ComputerMachine::new(1024).unwrap();
    let first = image(vec![Instruction::ReturnUnit], 0);
    let second = image(vec![Instruction::ReturnUnit], 0);

    assert_eq!(machine.spawn_boot_cpu(first, 128).unwrap(), 0);

    let error = machine.spawn_boot_cpu(second, 128).unwrap_err();
    assert_eq!(error, "boot CPU is already spawned");
}

#[test]
fn computer_machine_rejects_missing_cpu_id() {
    let mut machine = ComputerMachine::new(1024).unwrap();

    let error = machine.run_cpu_until_signal(7).unwrap_err();

    assert_eq!(error, "CPU 7 is not present");
}
```

- [ ] **Step 2: Run focused guardrail tests**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml computer_machine_rejects
```

Expected: pass.

- [ ] **Step 3: Commit**

```bash
git add native/rux-vm/src/computer_machine.rs
git commit -m "Guard computer boot CPU ownership"
```

## Task 6: Final Verification

**Files:**
- No code changes expected.

- [ ] **Step 1: Run Rust tests**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml
```

Expected: all Rust tests pass.

- [ ] **Step 2: Run compiler tests**

Run:

```bash
./gradlew :compiler:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Check whitespace**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 4: Confirm clean status**

Run:

```bash
git status --short
```

Expected: no output.

