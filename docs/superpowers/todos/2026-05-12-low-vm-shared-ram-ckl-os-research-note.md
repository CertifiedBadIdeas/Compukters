# Low VM Shared RAM / CKL OS Research Note

## Context

The `low-vm-shared-ram-ckl-os` branch explored a lower-level machine model for the CKL low VM:

- a reusable CPU/ISA core;
- machine-owned RAM;
- a `MemoryBus` abstraction;
- MMIO devices;
- separate `ComputerMachine` and `MicrocontrollerMachine` wrappers;
- a possible future where computer process semantics move into a CKL OS.

The work proved the direction is technically viable, but it is not the right next step for time-to-market.

## What Worked

- `LowImageCpu` can run against an external memory/bus object instead of owning process-local memory.
- `MachineBus` can route ordinary RAM accesses and MMIO accesses without the CPU knowing about devices.
- `ComputerMachine` can own shared physical RAM that is visible across CPU contexts.
- `ComputerMachine` can expose simple control registers through MMIO.
- `MicrocontrollerMachine` can reuse the same CPU core with a different board/device layout.

These are useful ideas and can be revived later if the project needs a more hardware-like architecture.

## What Became Too Expensive

The branch started moving toward a full platform architecture:

- explicit board memory maps;
- boot CPU semantics;
- MMIO control devices;
- future display/storage/input devices;
- CKL OS responsibilities;
- potential ABI/constants export for CKL.

That direction is coherent, but it shifts effort away from the currently shippable product. It would require substantial CKL runtime, driver, and OS work before users see a clear Minecraft-side improvement.

## Decision

Do not merge this branch into `dev` for now.

Treat it as research. Keep the branch/worktree available as a reference, but return product work to the current Rust VM, daemon, scheduler, hostcalls, filesystem, display, shell, ROM, and CKL compatibility path.

## Time-To-Market Path

Prioritize:

- current low VM compatibility with CKL;
- ROM/core program quality;
- shell and terminal UX;
- filesystem/process behavior;
- display polish;
- production jar sanity;
- clear errors and fewer runtime surprises.

Avoid for now:

- CKL OS;
- full hardware board model;
- broad MMIO device architecture;
- replacing the current daemon/process model;
- ABI export/generation infrastructure unless directly needed by current shipping work.

## Ideas Worth Reusing Later

- Keep `MemoryBus`/`MachineBus` as a reference for future device modeling.
- Keep the split between CPU core and machine wrapper as a future direction.
- Use RAM-backed buffers plus MMIO control registers for large devices like display or storage if the hardware-like model returns.
- If CKL OS becomes a priority, restart from a small bootable kernel and one real device path instead of building metadata infrastructure first.

## Current Recommendation

Resume work on `dev`.

Use this branch only as a sketchpad for future architecture, not as the main delivery path.

## 2026-05-13 Update: Bare-Metal Program Direction

The branch is active again, but with a narrower target than a full CKL OS.

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

## 2026-05-13 Update: Bare-Metal ABI v0

The active experiment now has a narrow ABI boundary instead of an implicit `ComputerMachine` contract.

ABI v0 defines:

- RAM base;
- control MMIO base, size, and status registers;
- debug serial MMIO base and write register;
- status values for reset, booting, ready, halted, and panic.

This is still not a CKL OS. The purpose is to make one bootable firmware program target a stable machine contract before any CKL compiler or runtime work is added on top.

## 2026-05-13 Update: Rust-Like Language Seed

The experiment now starts a new Rust-like bare-metal language instead of preserving CKL syntax or Kotlin compiler compatibility.

Implemented seed:

- new `native/ckl-compiler` Rust crate;
- direct source-to-`low_image::Image` compilation;
- one `main` function;
- `i32` arithmetic returns;
- `unsafe` MMIO access through `mmio<i32>(addr).store(value)` and `.load()`;
- deterministic compile errors for unsupported seed inputs;
- end-to-end firmware test on `ComputerMachine` that writes `OK`, halts, and reports exit code `0`.

This keeps the branch focused on a tiny bootable vertical slice: source text, Rust compiler, low image, machine execution. It deliberately does not revive the larger CKL OS/process work yet.
