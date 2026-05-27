# Low VM CPU Core Machine Models Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the low VM into a reusable CPU/ISA core with machine-owned memory, then prepare separate `ComputerMachine` and `MicrocontrollerMachine` targets.

**Architecture:** Keep CKIM v5 encoding stable for the first implementation slice. Extract memory access from `LowState` into a small machine memory abstraction, then keep `LowImageVm` as a standalone CPU harness over one simple memory object. Subsequent tasks introduce named machine wrappers and move process semantics toward Rux OS code instead of expanding the Rust daemon.

**Tech Stack:** Rust 2021 (`native/rux-vm`), Kotlin/JVM compiler bindings/tests, CKIM v5 low image ABI, Gradle profiling tasks, Cargo tests.

---

## File Structure

- `native/rux-vm/src/low_machine.rs`
  - New focused module for machine memory and memory faults.
  - Owns `MachineMemory`, `MemoryFault`, and helpers for loading initialized CKIM memory sections.
- `native/rux-vm/src/low_image_runner.rs`
  - Keep immutable `LowProgram`.
  - Convert `LowState` into CPU execution state only: frames, registers, metrics, time-check counters.
  - Route `Load32` and `Store32` through `MachineMemory`.
- `native/rux-vm/src/lib.rs`
  - Export the new `low_machine` module.
- `native/rux-vm/tests/low_image_runner.rs`
  - Add public behavior tests for shared memory visibility and unchanged standalone runner behavior.
- `native/rux-vm/src/device_daemon.rs`
  - Later transition adapter only. Do not expand process semantics here.
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/low/*`
  - No first-slice ABI encoding changes.
- `docs/superpowers/specs/2026-05-12/2026-05-12-low-vm-cpu-core-machine-models-design.md`
  - Source design.

---

## Phase 1: CPU State And Machine Memory Boundary

### Task 1: Add Machine Memory Module

**Files:**
- Create: `native/rux-vm/src/low_machine.rs`
- Modify: `native/rux-vm/src/lib.rs`
- Test: `native/rux-vm/src/low_machine.rs`

- [ ] **Step 1: Write memory tests first**

Add this test module to the new file:

```rust
#[cfg(test)]
mod tests {
    use super::MachineMemory;

    #[test]
    fn machine_memory_loads_initial_sections_and_zeroes_the_rest() {
        let memory = MachineMemory::from_sections(16, &[1, 2], &[3, 4, 5], 3).unwrap();

        assert_eq!(memory.bytes(), &[1, 2, 3, 4, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
    }

    #[test]
    fn machine_memory_rejects_sections_that_do_not_fit() {
        let error = MachineMemory::from_sections(4, &[1, 2], &[3, 4], 1).unwrap_err();

        assert_eq!(error.to_string(), "memory sections require 5 bytes but memory size is 4");
    }

    #[test]
    fn machine_memory_loads_and_stores_i32_little_endian() {
        let mut memory = MachineMemory::zeroed(8).unwrap();

        memory.store_i32(2, 0x11223344).unwrap();

        assert_eq!(memory.load_i32(2).unwrap(), 0x11223344);
        assert_eq!(&memory.bytes()[2..6], &[0x44, 0x33, 0x22, 0x11]);
    }

    #[test]
    fn machine_memory_reports_out_of_bounds_ranges() {
        let memory = MachineMemory::zeroed(8).unwrap();

        let error = memory.load_i32(6).unwrap_err();

        assert_eq!(error.to_string(), "memory access 6..10 is outside 8 bytes");
    }
}
```

- [ ] **Step 2: Run the failing module test**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml low_machine
```

Expected: compile failure because `low_machine` and `MachineMemory` do not exist yet.

- [ ] **Step 3: Implement `MachineMemory`**

Create `native/rux-vm/src/low_machine.rs`:

```rust
use std::error::Error;
use std::fmt::{Display, Formatter};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MemoryFault {
    message: String,
}

impl MemoryFault {
    fn new(message: String) -> Self {
        Self { message }
    }
}

impl Display for MemoryFault {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.message)
    }
}

impl Error for MemoryFault {}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MachineMemory {
    bytes: Vec<u8>,
}

impl MachineMemory {
    pub fn zeroed(size: usize) -> Result<Self, MemoryFault> {
        if size == 0 {
            return Err(MemoryFault::new("memory size must be positive".to_string()));
        }
        Ok(Self {
            bytes: vec![0_u8; size],
        })
    }

    pub fn from_sections(
        memory_size: usize,
        rodata: &[u8],
        data: &[u8],
        bss_size: u32,
    ) -> Result<Self, MemoryFault> {
        let initialized = rodata
            .len()
            .checked_add(data.len())
            .and_then(|value| value.checked_add(bss_size as usize))
            .ok_or_else(|| MemoryFault::new("memory sections overflow".to_string()))?;
        if initialized > memory_size {
            return Err(MemoryFault::new(format!(
                "memory sections require {initialized} bytes but memory size is {memory_size}",
            )));
        }
        let mut memory = Self::zeroed(memory_size)?;
        memory.bytes[..rodata.len()].copy_from_slice(rodata);
        let data_start = rodata.len();
        memory.bytes[data_start..data_start + data.len()].copy_from_slice(data);
        Ok(memory)
    }

    pub fn bytes(&self) -> &[u8] {
        &self.bytes
    }

    pub fn load_i32(&self, address: u32) -> Result<i32, MemoryFault> {
        let bytes = self.range(address, 4)?;
        let mut raw = [0_u8; 4];
        raw.copy_from_slice(bytes);
        Ok(i32::from_le_bytes(raw))
    }

    pub fn store_i32(&mut self, address: u32, value: i32) -> Result<(), MemoryFault> {
        self.range_mut(address, 4)?.copy_from_slice(&value.to_le_bytes());
        Ok(())
    }

    fn range(&self, address: u32, size: usize) -> Result<&[u8], MemoryFault> {
        let start = address as usize;
        let end = start
            .checked_add(size)
            .ok_or_else(|| MemoryFault::new(format!("memory access starts at {address} and overflows usize")))?;
        self.bytes.get(start..end).ok_or_else(|| {
            MemoryFault::new(format!(
                "memory access {start}..{end} is outside {} bytes",
                self.bytes.len(),
            ))
        })
    }

    fn range_mut(&mut self, address: u32, size: usize) -> Result<&mut [u8], MemoryFault> {
        let start = address as usize;
        let end = start
            .checked_add(size)
            .ok_or_else(|| MemoryFault::new(format!("memory access starts at {address} and overflows usize")))?;
        let len = self.bytes.len();
        self.bytes
            .get_mut(start..end)
            .ok_or_else(|| MemoryFault::new(format!("memory access {start}..{end} is outside {len} bytes")))
    }
}
```

Modify `native/rux-vm/src/lib.rs`:

```rust
pub mod low_machine;
```

- [ ] **Step 4: Run the module test**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml low_machine
```

Expected: all `low_machine` tests pass.

- [ ] **Step 5: Commit**

```bash
git add native/rux-vm/src/lib.rs native/rux-vm/src/low_machine.rs
git commit -m "Add low VM machine memory"
```

### Task 2: Move LowState Off Raw Memory

**Files:**
- Modify: `native/rux-vm/src/low_image_runner.rs`
- Test: `native/rux-vm/tests/low_image_runner.rs`

- [ ] **Step 1: Add a public shared-memory behavior test**

Append this test to `native/rux-vm/tests/low_image_runner.rs`:

```rust
#[test]
fn runner_can_be_created_with_external_machine_memory() {
    let writer = image(
        vec![
            Instruction::AddrConst { dst: 0, value: 128 },
            Instruction::I32Const { dst: 1, value: 77 },
            Instruction::Store32 { addr: 0, src: 1 },
            Instruction::ReturnUnit,
        ],
        2,
    );
    let reader = image(
        vec![
            Instruction::AddrConst { dst: 0, value: 128 },
            Instruction::Load32 { dst: 1, addr: 0 },
            Instruction::ReturnI32 { src: 1 },
        ],
        2,
    );
    let mut memory = rux_vm::low_machine::MachineMemory::zeroed(1024).unwrap();
    {
        let mut writer_vm = LowImageVm::create_cpu_with_memory(writer, 128, &mut memory).unwrap();
        assert_eq!(writer_vm.run_until_signal().unwrap(), LowImageSignal::HaltUnit);
    }
    {
        let mut reader_vm = LowImageVm::create_cpu_with_memory(reader, 128, &mut memory).unwrap();
        assert_eq!(reader_vm.run_until_signal().unwrap(), LowImageSignal::HaltI32(77));
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml runner_can_be_created_with_external_machine_memory
```

Expected: compile failure because `LowImageVm::create_cpu_with_memory` does not exist.

- [ ] **Step 3: Refactor low runner ownership**

In `native/rux-vm/src/low_image_runner.rs`:

1. Add import:

```rust
use crate::low_machine::MachineMemory;
```

2. Change `LowProgram::create(image)` so it returns `LowProgram` and `LowState`, but no longer allocates `Vec<u8>`.

3. Remove `memory: Vec<u8>` from `LowState`.

4. Keep `LowImageVm` as the owned standalone harness:

```rust
pub struct LowImageVm {
    program: LowProgram,
    state: LowState,
    memory: MachineMemory,
    slice_budget: Duration,
}
```

5. Add a borrowed CPU runner for shared machine memory:

```rust
pub struct LowImageCpu<'memory> {
    program: LowProgram,
    state: LowState,
    memory: &'memory mut MachineMemory,
    slice_budget: Duration,
}
```

6. Add the borrowed constructor as an associated function on `LowImageVm`, so tests can discover it next to the existing constructor:

```rust
impl LowImageVm {
    pub fn create_cpu_with_memory<'memory>(
        image: Image,
        slice_budget_nanos: u64,
        memory: &'memory mut MachineMemory,
    ) -> Result<LowImageCpu<'memory>, String> {
        let (program, state) = LowProgram::create(image)?;
        Ok(LowImageCpu {
            program,
            state,
            memory,
            slice_budget: Duration::from_nanos(slice_budget_nanos.max(1)),
        })
    }
}
```

7. Give both harnesses the same runner method shape:

```rust
fn run_cpu_until_signal(
    program: &LowProgram,
    state: &mut LowState,
    memory: &mut MachineMemory,
    slice_budget: Duration,
) -> Result<LowImageSignal, String> {
    state.metrics.run_invocations = state.metrics.run_invocations.saturating_add(1);
    let started_at = Instant::now();
    loop {
        let (function_index, block_index) = {
            let frame = state.current_frame();
            (frame.function_index, frame.block_index)
        };
        let block = program.function(function_index).block(block_index);
        for operation in &block.operations {
            state.execute_operation(memory, operation)?;
            if state.should_pause(started_at, slice_budget) {
                return Ok(LowImageSignal::Pause);
            }
        }
        if let Some(signal) = state.execute_terminator(program, &block.terminator, started_at)? {
            return Ok(signal);
        }
        if state.should_pause(started_at, slice_budget) {
            return Ok(LowImageSignal::Pause);
        }
    }
}

impl LowImageVm {
    pub fn run_until_signal(&mut self) -> Result<LowImageSignal, String> {
        run_cpu_until_signal(&self.program, &mut self.state, &mut self.memory, self.slice_budget)
    }
}

impl LowImageCpu<'_> {
    pub fn run_until_signal(&mut self) -> Result<LowImageSignal, String> {
        run_cpu_until_signal(&self.program, &mut self.state, self.memory, self.slice_budget)
    }
}
```

Use `LowImageVm` for standalone tests and `LowImageCpu` for shared memory tests. This avoids self-referential ownership and keeps the public standalone JNI path stable.

8. Move `Load32`/`Store32` memory work out of `LowState::execute_operation` into a method that receives `&mut MachineMemory`.

```rust
fn execute_operation(
    &mut self,
    memory: &mut MachineMemory,
    operation: &ExecutableOperation,
) -> Result<(), String>
```

7. Replace memory range calls:

```rust
let value = memory.load_i32(address).map_err(|error| error.to_string())?;
self.write_i32(*dst, value);
```

and:

```rust
memory
    .store_i32(address, self.read_i32(*src))
    .map_err(|error| error.to_string())?;
```

- [ ] **Step 4: Run the focused test**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml runner_can_be_created_with_external_machine_memory
```

Expected: the shared-memory test passes.

- [ ] **Step 5: Run all low image runner tests**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml low_image_runner
```

Expected: all low image runner tests pass.

- [ ] **Step 6: Commit**

```bash
git add native/rux-vm/src/low_image_runner.rs native/rux-vm/tests/low_image_runner.rs
git commit -m "Separate low VM CPU state from machine memory"
```

## Phase 2: Machine Targets Without ABI Churn

### Task 3: Add Named Computer Machine Harness

**Files:**
- Create: `native/rux-vm/src/computer_machine.rs`
- Modify: `native/rux-vm/src/lib.rs`
- Test: `native/rux-vm/src/computer_machine.rs`

- [ ] **Step 1: Write a computer-machine test**

Create `native/rux-vm/src/computer_machine.rs` with this initial test module:

```rust
#[cfg(test)]
mod tests {
    use crate::computer_machine::ComputerMachine;

    #[test]
    fn computer_machine_owns_shared_physical_ram() {
        let mut machine = ComputerMachine::new(1024).unwrap();

        machine.memory_mut().store_i32(128, 42).unwrap();

        assert_eq!(machine.memory().load_i32(128).unwrap(), 42);
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml computer_machine_owns_shared_physical_ram
```

Expected: compile failure because `ComputerMachine` does not exist.

- [ ] **Step 3: Implement the minimal wrapper**

Add above the test module:

```rust
use crate::low_machine::{MachineMemory, MemoryFault};

pub struct ComputerMachine {
    memory: MachineMemory,
}

impl ComputerMachine {
    pub fn new(memory_size: usize) -> Result<Self, MemoryFault> {
        Ok(Self {
            memory: MachineMemory::zeroed(memory_size)?,
        })
    }

    pub fn memory(&self) -> &MachineMemory {
        &self.memory
    }

    pub fn memory_mut(&mut self) -> &mut MachineMemory {
        &mut self.memory
    }
}
```

Modify `native/rux-vm/src/lib.rs`:

```rust
pub mod computer_machine;
```

- [ ] **Step 4: Run the test**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml computer_machine_owns_shared_physical_ram
```

Expected: test passes.

- [ ] **Step 5: Commit**

```bash
git add native/rux-vm/src/lib.rs native/rux-vm/src/computer_machine.rs
git commit -m "Add low VM computer machine harness"
```

### Task 4: Add Microcontroller Machine Skeleton

**Files:**
- Create: `native/rux-vm/src/microcontroller_machine.rs`
- Modify: `native/rux-vm/src/lib.rs`
- Test: `native/rux-vm/src/microcontroller_machine.rs`

- [ ] **Step 1: Write a microcontroller-machine test**

Create `native/rux-vm/src/microcontroller_machine.rs` with this initial test module:

```rust
#[cfg(test)]
mod tests {
    use crate::microcontroller_machine::MicrocontrollerMachine;

    #[test]
    fn microcontroller_machine_has_small_ram_and_no_process_model() {
        let machine = MicrocontrollerMachine::new(256).unwrap();

        assert_eq!(machine.memory().bytes().len(), 256);
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml microcontroller_machine_has_small_ram_and_no_process_model
```

Expected: compile failure because `MicrocontrollerMachine` does not exist.

- [ ] **Step 3: Implement the minimal skeleton**

Add above the test module:

```rust
use crate::low_machine::{MachineMemory, MemoryFault};

pub struct MicrocontrollerMachine {
    memory: MachineMemory,
}

impl MicrocontrollerMachine {
    pub fn new(memory_size: usize) -> Result<Self, MemoryFault> {
        Ok(Self {
            memory: MachineMemory::zeroed(memory_size)?,
        })
    }

    pub fn memory(&self) -> &MachineMemory {
        &self.memory
    }
}
```

Modify `native/rux-vm/src/lib.rs`:

```rust
pub mod microcontroller_machine;
```

- [ ] **Step 4: Run the test**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml microcontroller_machine_has_small_ram_and_no_process_model
```

Expected: test passes.

- [ ] **Step 5: Commit**

```bash
git add native/rux-vm/src/lib.rs native/rux-vm/src/microcontroller_machine.rs
git commit -m "Add low VM microcontroller machine skeleton"
```

## Phase 3: Keep Runtime Stable While Preventing More Process Creep

### Task 5: Document Native Daemon As Transition Adapter

**Files:**
- Modify: `native/rux-vm/src/device_daemon.rs`
- Modify: `docs/superpowers/specs/2026-05-12/2026-05-12-low-vm-cpu-core-machine-models-design.md`

- [ ] **Step 1: Add a Rust module comment**

At the top of `native/rux-vm/src/device_daemon.rs`, add:

```rust
//! Transition adapter for the current computer runtime.
//!
//! New low-level execution work should target the CPU core and machine models.
//! Process semantics are intentionally not expanded here; the long-term
//! computer process model belongs in Rux OS code.
```

- [ ] **Step 2: Add a short note to the design**

In the design document, under `Migration Plan`, add:

```markdown
During the transition, `device_daemon.rs` may continue to run existing computer ROM behavior. New semantics should not be added there unless they are needed to keep current tests green while the Rux OS replacement is being built.
```

- [ ] **Step 3: Run formatting/checks**

Run:

```bash
cargo fmt --manifest-path native/rux-vm/Cargo.toml
git diff --check
```

Expected: both commands exit successfully.

- [ ] **Step 4: Commit**

```bash
git add native/rux-vm/src/device_daemon.rs docs/superpowers/specs/2026-05-12/2026-05-12-low-vm-cpu-core-machine-models-design.md
git commit -m "Mark native daemon as transition adapter"
```

## Phase 4: Verification And Benchmark Guardrail

### Task 6: Run Full Native Verification

**Files:**
- No source changes expected.

- [ ] **Step 1: Run native test suite**

Run:

```bash
cargo test --manifest-path native/rux-vm/Cargo.toml
```

Expected: all native Rust tests pass.

- [ ] **Step 2: Run low benchmark once**

Run:

```bash
./gradlew profileComputeVmBenchmarkRelease
```

Expected: task succeeds and writes a timestamped report under `modules/compiler/build/reports/profiling/`.

- [ ] **Step 3: Check workspace**

Run:

```bash
git status --short
```

Expected: only generated benchmark reports may be untracked/modified. Commit source/docs changes only.

- [ ] **Step 4: Commit benchmark metadata only if source reporting changed**

No commit is required for generated reports unless the user explicitly wants benchmark reports tracked.

## Explicitly Deferred Work

- Full Rux OS implementation.
- Removing the current native process daemon.
- MMIO instruction extensions.
- Trap ABI redesign.
- CKIM segment relocation.
- Microcontroller block/item gameplay implementation.
