# Bare-Metal Computer ABI v0 Design

## Goal

Define a small, explicit ABI between a single low VM firmware program and `ComputerMachine`.

The ABI must make the experimental bare-metal computer path stable enough for the next milestone: compiling CKL programs as one bootable firmware image, without starting a CKL OS, process table, scheduler, filesystem, or display stack.

## Motivation

The current branch already has the useful primitives:

- `ComputerMachine` owns shared physical RAM.
- `MachineBus` routes RAM and MMIO accesses.
- One boot CPU can run one low VM image.
- Control MMIO exposes status, panic code, and exit code.
- Debug serial MMIO captures deterministic output.

Those pieces are currently embedded directly in `ComputerMachine`. That is fine for tests, but it is a weak contract for a compiler target. A CKL bare-metal target should depend on a clear ABI, not on implementation details.

## Non-Goals

- Do not add CKL OS processes.
- Do not add guest scheduling.
- Do not add virtual memory.
- Do not add filesystem, display, terminal, or shell integration.
- Do not add a CKL compiler target in this slice.
- Do not preserve a fallback path for older experimental ABI shapes.

## ABI v0 Shape

ABI v0 is a single-program firmware contract:

```text
ComputerMachine
  RAM:       0x0000_0000 .. memory_size
  control:  0x1000_0000 .. 0x1000_000c
  debug:    0x1000_0100 .. 0x1000_0104

boot CPU:
  one low VM image
  entry function from image.entry_function_index
  shared RAM through MachineBus
  MMIO through MachineBus
```

The firmware may write RAM and MMIO directly with low VM load/store instructions. The host owns the machine and interprets final machine state after the boot CPU returns or faults.

## Public ABI Module

Add a small Rust module for stable constants:

```rust
pub mod computer_abi {
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
}
```

`ComputerMachine` keeps compatibility constants for now, but they become aliases to `computer_abi`. This keeps existing tests and call sites simple while moving the source of truth out of the machine implementation.

## Control MMIO

The control device is a 12-byte little-endian `i32` register block:

```text
offset 0x00: status
offset 0x04: panic_code
offset 0x08: exit_code
```

Status meanings:

- `RESET`: machine was created and no firmware state has been reported yet.
- `BOOTING`: firmware has started boot logic.
- `READY`: firmware reached its own ready state.
- `HALTED`: host observed a firmware halt signal and stored the final exit code.
- `PANIC`: host observed a VM fault and stored a stable non-zero panic code.

Firmware may write `BOOTING`, `READY`, or `PANIC` directly. The host writes `HALTED` when `run_boot_cpu_until_signal` observes a halt signal. The host writes `PANIC` when low VM execution returns an error.

## Debug Serial MMIO

The debug device is a 4-byte write register:

```text
offset 0x00: write byte
```

Writing an `i32` appends its low 8 bits to the debug output buffer. Reading the register returns `0`.

This is intentionally not a terminal. It is a deterministic test/debug channel for firmware and future compiler-target smoke tests.

## Memory Map API

`ComputerMachine::memory_map()` remains the inspection API, but it should report values from `computer_abi`:

- `ram`: base `RAM_BASE`, size `machine.memory().len()`
- `control`: base `CONTROL_BASE`, size `CONTROL_SIZE`
- `debug`: base `DEBUG_BASE`, size `DEBUG_SIZE`

This makes the map test prove that runtime machine configuration matches ABI constants.

## Firmware Lifecycle

The MVP lifecycle is:

1. Host creates `ComputerMachine`.
2. Host spawns exactly one boot CPU with a low VM image.
3. Firmware optionally writes `STATUS_BOOTING`.
4. Firmware may write RAM and debug serial bytes.
5. Firmware optionally writes `STATUS_READY`.
6. Firmware returns a low VM halt signal.
7. Host maps the halt signal to `STATUS_HALTED` and `exit_code`.
8. If execution faults, host maps the fault to `STATUS_PANIC` and `panic_code`.

The lifecycle does not include process creation, preemptive scheduling, or guest-managed process tables.

## Error Handling

ABI v0 keeps errors simple:

- Invalid MMIO offset returns a `MemoryFault`.
- Overlapping MMIO regions are rejected by `MachineBus`.
- A boot CPU fault marks the machine as `PANIC`.
- Panic codes are stable for the same error message during tests.
- Non-boot CPUs cannot be run through `run_boot_cpu_until_signal`.

## Testing

The implementation should add tests for:

- `ComputerMachine` constants match `computer_abi`.
- `memory_map()` reports ABI-defined bases and sizes.
- A firmware fixture can use only `computer_abi` constants to write `OK`, mark ready, and halt.
- Control/debug device sizes are ABI-defined rather than private duplicate values.

Existing `legacy_ckl_os_research_*` tests stay as research references and should not be expanded in this milestone.

## Success Criteria

- ABI constants have one source of truth.
- Existing bare-metal tests still pass.
- The firmware smoke test uses ABI constants, not private machine literals.
- No CKL OS/process/scheduler work is added.
- `cargo test --manifest-path native/ckl-vm/Cargo.toml` passes.
