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
