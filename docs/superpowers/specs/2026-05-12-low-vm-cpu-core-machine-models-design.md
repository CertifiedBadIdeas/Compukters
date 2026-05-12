# Low VM CPU Core, Machine Models, And CKL OS Design

## Goal

Turn the low-level VM into a reusable CPU/ISA execution core rather than an OS-like process runtime.

The same CPU core should be reusable by different machine models:

- `ComputerMachine` can boot a CKL mini OS that implements processes, loading, stdio, and shell behavior;
- `MicrocontrollerMachine` can run one firmware image directly, with no process table, virtual memory, display stack, or OS assumptions.

The important distinction is that a microcontroller is not a smaller computer. It is a different machine built around the same execution core.

## Motivation

The current runtime grew toward a Rust-owned native daemon:

- Rust owns process scheduling;
- each process owns an image VM handle;
- low VM linear RAM is currently allocated per `LowImageVm`;
- process APIs such as `run`, `spawn`, and `wait` are runtime concepts.

That is useful for getting a Unix-like computer experience quickly, but it makes the CPU core less reusable. If the execution core knows about processes, working directories, stdio, and child program loading, it becomes awkward to embed in other devices. A microcontroller should not need a process manager just to blink, poll peripherals, or run firmware.

The new direction is to make the CPU core deliberately dumb:

- execute instructions;
- expose registers, program counter, and traps/signals;
- perform memory load/store through a machine-provided memory bus;
- enforce instruction/time guard hooks;
- avoid built-in process, filesystem, stdio, or virtual-memory semantics.

Everything higher-level should be CKL code or a concrete machine/device model.

## Non-Goals

- Do not implement the full CKL OS in this slice.
- Do not add virtual memory.
- Do not add memory protection between CKL-level processes in the CPU core.
- Do not keep Kotlin or old VM fallbacks.
- Do not require microcontrollers to support process APIs.
- Do not turn the first slice into a full ABI redesign.

## Core Decision

Split the runtime into four conceptual layers:

```text
Minecraft host
  -> machine model
       -> CPU/ISA core
       -> machine memory map
       -> machine devices/peripherals
       -> trap/interrupt boundary
       -> quota/time guard
  -> firmware payload
       -> CKL OS for computers
       -> single CKL firmware for microcontrollers
```

The CPU core is not the whole device. It does not know whether the running code is a BIOS, OS kernel, user process, daemon, shell, or microcontroller firmware. `ComputerMachine` and `MicrocontrollerMachine` decide what memory, devices, traps, and boot protocol exist around the core.

## CPU Core Responsibilities

The CPU core owns:

- immutable decoded program code or firmware image;
- mutable CPU execution state;
- fixed-width primitive registers;
- instruction dispatch;
- memory load/store requests through a machine memory interface;
- time-slice guard integration;
- halt, pause, trap, and error signals;

It does not own:

- physical RAM allocation;
- device memory maps;
- display, storage, GPIO, timers, or peripheral buses;
- process IDs;
- process parent/child relationships;
- current working directory;
- `process.run`, `process.spawn`, or `process.wait`;
- stdio descriptors;
- filesystem path resolution policy;
- heap object model;
- virtual address spaces.

This keeps the CPU core reusable for devices that are not computers.

## Machine Models

Different device types embed the same CPU core into different hardware models.

### ComputerMachine

A computer machine has:

- larger shared RAM;
- display hardware;
- filesystem/storage hardware;
- event/input hardware;
- optional network/peripheral hardware;
- a boot image that is a CKL OS kernel or BIOS.

Processes are CKL OS data structures. The CPU core sees only execution state and memory operations. The computer machine may later support multiple hardware CPU contexts, but OS processes are not built into the core.

### MicrocontrollerMachine

A microcontroller machine has:

- smaller RAM;
- no process APIs;
- no filesystem requirement;
- GPIO/redstone/peripheral-bus style devices;
- timers/interrupt-like events;
- a single firmware image;
- simple halt/reset behavior;
- optional persistent configuration, not a computer filesystem.

The microcontroller still benefits from the same instruction set, ABI tooling, benchmarks, and low-level compiler backend, but it is not required to expose computer devices.

## RAM Model

RAM belongs to the machine model, not the CPU core.

```rust
trait MemoryBus {
    fn load_u32(&self, address: u32) -> Result<u32, MemoryFault>;
    fn store_u32(&mut self, address: u32, value: u32) -> Result<(), MemoryFault>;
}

struct ComputerMemory {
    bytes: Vec<u8>,
}

struct MicrocontrollerMemory {
    bytes: Vec<u8>,
    mmio: PeripheralMap,
}
```

Guest addresses are `u32`. How they map to RAM, MMIO, or traps is machine-specific.

For `ComputerMachine`, RAM is physical and shared by the OS:

```text
0x0000_0000..0x0000_3fff  firmware/kernel
0x0000_4000..0x0000_7fff  kernel heap
0x0000_8000..0x0000_bfff  process table and descriptors
0x0000_c000..             user program allocations
```

Those regions are OS policy, not VM policy.

For `MicrocontrollerMachine`, the map can be simpler and device-like:

```text
0x0000_0000..0x0000_1fff  RAM
0x1000_0000..0x1000_00ff  GPIO/redstone MMIO
0x1000_0100..0x1000_01ff  timer MMIO
```

This lets firmware use the same load/store instructions for memory-mapped devices without inheriting computer process semantics.

## Program Loading

The first CPU-core-oriented implementation should keep decoded instructions outside guest RAM for simplicity and speed. RAM holds data, stacks, heaps, buffers, and OS-owned program images.

Later, if hand-editable binaries and bootloaders need stronger realism, CKIM can evolve toward an object format with loadable segments:

```text
.text    executable code segment
.rodata  read-only bytes by convention
.data    initialized mutable bytes
.bss     zero-filled bytes
```

The key change is semantic: these segments load into machine memory at loader-chosen addresses, not into private per-process memory owned by the CPU core.

## CKL OS Responsibilities

The computer OS written in CKL owns:

- memory allocation;
- program loading;
- process descriptors;
- scheduling policy;
- exit codes;
- stdin/stdout/stderr conventions;
- shell job model;
- current directory;
- filesystem path normalization;
- IPC abstractions;
- terminal sessions.

This turns current `process::*` behavior from VM/runtime policy into CKL library and OS behavior.

The OS may start simple:

```text
bios.ck
  -> kernel.ck
       -> init display/session
       -> run shell loop
       -> load and call programs cooperatively
```

It does not need preemptive multitasking in the first slice. Cooperative calls and explicit yields are enough while the machine model settles.

## Microcontroller Firmware Responsibilities

Microcontroller firmware written in CKL owns:

- initialization;
- main loop;
- peripheral polling;
- interrupt/event handling policy;
- memory layout conventions;
- any tiny allocator or buffer management it needs.

It does not need:

- process descriptors;
- child program loading;
- stdio conventions;
- shell job control;
- filesystem path handling.

## Trap And Device Boundary

The CPU core should expose low-level traps instead of high-level process hostcalls.

Examples:

- `trap display_present`;
- `trap filesystem_read_sector` or higher-level storage operation during transition;
- `trap event_poll`;
- `trap peripheral_call`;
- `trap clock_ticks`;
- `trap shutdown`.

The machine model maps traps to Minecraft/device behavior. The CKL OS or firmware wraps traps into friendly APIs.

For a microcontroller, traps may be even smaller:

- GPIO read/write;
- redstone input/output;
- timer;
- peripheral bus call.

## ABI Direction

CKIM should become a CPU image format plus machine metadata rather than a process image format.

Near-term:

- keep CKIM v5 instructions where possible;
- keep `u32` addresses;
- replace per-image RAM ownership in the runner with machine-owned memory;
- preserve tests with a standalone CPU harness using a simple memory bus.

Later:

- add explicit machine target metadata;
- add load address or relocatable segment metadata;
- add trap/import table for hardware devices;
- remove process-specific imports from the low VM ABI.

## Migration Plan

1. Introduce a `MemoryBus`/machine-memory abstraction in Rust and move RAM access behind it.
2. Split low VM mutable state into CPU execution context and machine-owned memory.
3. Rename public concepts away from process wording where they are really CPU contexts.
4. Adapt standalone low VM tests to run through a single CPU harness with a simple machine memory implementation.
5. Introduce `ComputerMachine` with one shared RAM block.
6. Keep the current native daemon as a transition adapter, but stop expanding its process semantics.
7. Move process behavior into CKL ROM/OS code in later slices.
8. Add `MicrocontrollerMachine` that boots one firmware image without process APIs.

## First Implementation Slice

The first slice should be intentionally small:

- add a machine memory abstraction;
- remove raw `Vec<u8>` memory ownership from `LowState`;
- make execution read/write through the memory abstraction;
- keep `LowImageVm` as a standalone harness that owns one simple memory object;
- do not change CKIM encoding yet;
- add tests proving two CPU contexts can observe the same RAM through one memory object.

This gives us the CPU/machine boundary without forcing the CKL OS or microcontroller target to exist immediately.

## Risks

### Risk: Losing Useful Process Functionality Too Early

The current ROM depends on process APIs. Removing them before CKL OS support exists would break the computer experience.

Mitigation: keep the old process daemon only as a transition adapter in this branch. Do not expand it. Move behavior into CKL OS one subsystem at a time.

### Risk: Shared RAM Makes Bugs Harder

Without virtual memory, one bad CKL program can corrupt OS memory on `ComputerMachine`.

Mitigation: accept this as hardware realism for the low-level computer. In early computer profiles, the CKL OS can use conventions and debug checks. Microcontrollers benefit from the simpler model because there are no user processes to isolate.

### Risk: Machine Models Drift Apart

If `ComputerMachine` and `MicrocontrollerMachine` evolve independently, the shared CPU core may become an abstraction in name only.

Mitigation: keep instruction execution, validation, register state, time guarding, and compiler backend shared. Machine differences should live behind memory and trap interfaces.

### Risk: ABI Confusion

Existing CKIM fields such as `memory_size`, `.data`, and `.bss` currently imply per-image memory.

Mitigation: document the semantic shift before changing encoding. The first slice keeps the bytes stable while moving ownership in Rust.

## Success Criteria

- The CPU core can run an existing low image with behavior unchanged.
- RAM is represented as machine-owned memory, not process-owned memory.
- Tests prove multiple CPU contexts can share one RAM object.
- The design no longer requires process semantics for microcontroller firmware.
- `ComputerMachine` and `MicrocontrollerMachine` are separate targets around the same CPU core.
- Future process APIs are clearly CKL OS responsibilities, not VM hardware responsibilities.
