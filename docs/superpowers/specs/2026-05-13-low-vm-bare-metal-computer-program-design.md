# Low VM Bare-Metal Computer Program Design

## Goal

Build the next experimental computer model around one bare-metal low VM program that boots directly on a `ComputerMachine`.

This is not a full CKL OS slice yet. The first target is closer to firmware: one program owns the whole guest RAM, starts at one entry function, talks to a tiny machine ABI, and can later grow into an OS kernel.

## Motivation

The previous shared-RAM CKL OS experiment proved useful architectural ideas:

- `ComputerMachine` can own RAM and CPU contexts.
- `LowCpuContext` can execute against a machine-owned memory bus.
- MMIO/control registers can expose machine state to guest code.
- Guest RAM can hold OS-like state.

It also started pulling the branch toward a full OS too early: process tables, scheduler state, static user processes, future loaders, and device metadata. That is coherent, but too much before we have a clean bootable low-level computer path.

The next slice should prove a smaller and more fundamental thing: a low VM program can run as the only software on a computer-shaped machine.

## Non-Goals

- Do not implement guest processes.
- Do not implement a guest scheduler.
- Do not implement virtual memory.
- Do not implement user/kernel privilege separation.
- Do not expose filesystem, terminal, shell, or display drivers in the first slice.
- Do not merge this branch into `dev` until the bare-metal path is proven and intentionally reviewed.
- Do not preserve the old CKL OS process-table fixtures as the main direction.

## Architecture

The first bare-metal runtime shape is:

```text
ComputerMachine
  owns MachineBus
    owns guest RAM
    owns minimal control/debug MMIO
  owns one boot CPU context
  boots one low VM image
  runs until signal or host-side time budget
```

The guest program is the entire machine software stack for this slice. It is not a process inside a Rust daemon process table. It receives a flat machine environment and is responsible for its own code-level state.

Rust still owns:

- machine creation;
- RAM allocation and quota enforcement;
- CPU stepping and wall-time guard;
- low VM image decode and validation;
- minimal MMIO device surfaces;
- test harness inspection.

Guest code owns:

- normal program control flow;
- RAM data structures;
- boot status writes;
- debug/serial output writes;
- final halt or panic code.

## Boot Model

The machine boots exactly one image:

```rust
let mut machine = ComputerMachine::new(ram_bytes)?;
let cpu_id = machine.spawn_boot_cpu(image, slice_budget_nanos)?;
let result = machine.run_cpu_until_signal(cpu_id)?;
```

The first slice keeps decoded instructions outside guest RAM. Guest RAM contains data, stacks, and later boot info. This avoids turning the image into a full object-loader problem before we need it.

The machine rejects a second boot CPU. Multiple CPU contexts can remain an experiment, but the bare-metal MVP starts with one CPU.

## Guest RAM

RAM is one flat byte-addressable space owned by `ComputerMachine`.

The guest program sees `u32` addresses. Every memory access remains bounds-checked by Rust. Out-of-bounds access is a VM error, not undefined behavior.

The first slice does not need a guest allocator. Fixtures can write fixed addresses. Later CKL runtime support can introduce stack/heap conventions.

Suggested initial layout:

```text
0x0000_0000..0x0000_00ff  boot scratch / status words
0x0000_0100..0x0000_0fff  firmware globals and buffers
0x0000_1000..end          free firmware-owned RAM
```

This layout is intentionally provisional. It is enough to write observable state without committing to a full kernel memory map.

## Minimal Machine ABI

The MVP should expose the smallest useful observable surface:

```text
CONTROL_STATUS_ADDR      u32
CONTROL_EXIT_CODE_ADDR   i32
CONTROL_PANIC_CODE_ADDR  i32
DEBUG_WRITE_ADDR         i32 write-only byte/char register
```

Possible values:

```text
status = 0  cold/unknown
status = 1  booting
status = 2  ready
status = 3  halted
status = 4  panicked
```

The first visible output should be a debug/serial buffer, not a display. A serial-style device is cheaper, deterministic in tests, and avoids building a display driver before the boot model is stable.

Display, keyboard/input, filesystem, and IPC should come later as separate slices.

## Signals

The CPU can still return existing low VM signals:

- `Halt(exit_code)`
- `Pause`
- `Yield`
- `Sleep(ticks)`
- `Error(message)`

For the bare-metal slice:

- `Halt(0)` means the firmware completed successfully.
- non-zero halt codes mean firmware-level failure.
- VM errors mark the machine as panicked or faulted.
- `Yield` and `Pause` remain useful for cooperative stepping and time-budget tests.

The first slice does not need process wait, event wait, or hostcall signals.

## Relationship To Existing Experimental Code

Keep:

- `LowCpuContext`;
- `MachineMemory`;
- `MemoryBus`;
- `MachineBus`;
- `ComputerMachine` CPU ownership APIs;
- ready/panic boot fixture ideas.

De-emphasize for now:

- guest process table fixtures;
- scheduler fixtures;
- static user process fixture;
- CKL OS metadata and process-loader plans.

These older pieces are not necessarily wrong. They are just not part of the immediate path.

## Testing Strategy

Use Rust tests in `native/ckl-vm` first.

Initial tests:

- `bare_metal_program_marks_machine_ready`
- `bare_metal_program_writes_debug_output`
- `bare_metal_program_halts_with_exit_code`
- `bare_metal_program_fault_marks_machine_panicked`
- `computer_machine_rejects_second_boot_cpu`

Tests should inspect machine state through Rust APIs rather than depending on Minecraft, Kotlin, JNI, or the current daemon.

Once Rust boot semantics are stable, add Kotlin/JNI integration only if it directly supports a real product path.

## Rollout

1. Write a focused plan for the bare-metal MVP.
2. Add or rename tests away from CKL OS/process language.
3. Keep `ComputerMachine` as the machine wrapper for one boot program.
4. Add a deterministic debug/serial output surface.
5. Add a bare-metal low-image fixture that writes ready/debug/halt.
6. Run the existing low VM tests to ensure the normal low image runner still works.
7. Decide whether the next device is display or keyboard/input.

## Success Criteria

- A `ComputerMachine` can boot one low VM image as bare-metal firmware.
- The firmware can write a ready status into machine-visible state.
- The firmware can write deterministic debug output.
- The firmware can halt with an exit code.
- A VM fault is visible as a machine panic/fault state.
- No guest process table or scheduler is required for the MVP.

## Design Notes

This direction still supports the long-term OS dream. The difference is sequencing:

```text
bare-metal program
  -> firmware with serial/debug
  -> firmware with display/input
  -> kernel-shaped firmware
  -> optional process model
  -> optional CKL OS
```

The branch should stay fun and ambitious, but each slice should boot, run, and show one concrete machine behavior.
