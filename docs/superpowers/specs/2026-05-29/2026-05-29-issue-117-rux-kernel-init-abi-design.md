# Rux Kernel/Init ABI Design

> Issue: [#117](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/117)

## Context

The current boot chain already reaches user-space code from persistent storage:

`BIOS flash -> BOOT:/boot/loader.ruxe -> ROOT:/boot/kernel.ruxe -> ROOT:/bin/init.ruxe`

The implemented examples prove the mechanics, but two boundaries are still implicit:

- the bootloader-to-kernel handoff;
- the kernel-to-init handoff.

This spec makes both boundaries explicit while keeping the kernel small. The goal is not to build a full real-world kernel yet; the goal is to follow real boot architecture closely enough that later OS work has the right shape.

## Design Goals

- Keep `kernel.ruxe` and `/bin/init.ruxe` as different ABI roles.
- Keep `/bin/init.ruxe` a normal user-space `program` RUXE.
- Put boot information on the bootloader-to-kernel boundary, not on the kernel-to-init boundary.
- Avoid fallback paths. Missing or invalid init must fail explicitly.
- Avoid adding processes, privilege rings, syscalls, or virtual memory in this slice.
- Leave room for those features without changing the basic storage layout.

## Alternatives Considered

### A. Treat init as the kernel

The bootloader could execute `/bin/init.ruxe` directly and skip `kernel.ruxe`. This is smaller, but it removes the kernel boundary before the project has a place to grow OS responsibilities such as device arbitration, error policy, process setup, and future syscall dispatch.

### B. Make init a kernel RUXE

The kernel could load another kernel-profile executable. That keeps the ABI uniform inside privileged code, but it blurs user-space. It would make normal programs and init diverge immediately.

### C. Kernel loads a program RUXE as init

Recommended. `kernel.ruxe` remains the first OS-owned executable. The bootloader passes minimal boot information to the kernel. The kernel then loads `/bin/init.ruxe`, validates that it is a `program` RUXE, and jumps to init without inventing a process model yet.

## ABI v0

### Executable Roles

- `BOOT:/boot/loader.ruxe` is a bootloader RUXE.
- `ROOT:/boot/kernel.ruxe` is a kernel RUXE.
- `ROOT:/bin/init.ruxe` is a program RUXE.

The bootloader must reject `ROOT:/boot/kernel.ruxe` if its RUXE ABI kind is not `kernel`. The kernel must reject `/bin/init.ruxe` if its RUXE ABI kind is not `program`. Neither stage retries another path or silently boots another image.

### Storage Contract

The kernel looks for init at exactly:

```text
ROOT:/bin/init.ruxe
```

The first ABI version does not define a search path, initrd, boot menu, or configurable init path. Those can be later kernel policy.

### Bootloader-to-Kernel Boot Info

Real systems usually pass boot information from the bootloader to the kernel: memory layout, device description, command line, initrd pointers, or platform tables. Rux ABI v0 follows that shape, but keeps the payload small.

Before entering `kernel.ruxe`, the bootloader writes a boot info block at `0x3F00`:

```text
offset  size  field
0x00    4     magic: "RKBI"
0x04    2     version: 1
0x06    2     size_bytes
0x08    4     root_start_lba
0x0C    4     kernel_ruxe_size_bytes
0x10    4     flags
```

For ABI v0, `flags` is zero. The kernel reads this block by fixed address. A register-passed pointer can replace the fixed address later when the CPU/language ABI has a stable function-call convention.

### Kernel-to-Init Handoff Info

Real kernels usually enter the first user-space process with a small, explicit startup contract. Rux ABI v0 follows that shape without adding processes, syscalls, argv, env, or handles yet.

Before entering `/bin/init.ruxe`, the kernel writes an init handoff block at `0x3F20`:

```text
offset  size  field
0x00    4     magic: "RINI"
0x04    2     version: 1
0x06    2     size_bytes
0x08    4     root_start_lba
0x0C    4     init_ruxe_size_bytes
0x10    4     init_entry_pc
0x14    4     flags
```

For ABI v0, `flags` is zero. Init is not required to consume this block yet, but the kernel must write it before jumping so future init/runtime code has a stable ABI surface to read from.

### Kernel-to-Init Contract

The kernel:

1. finds `ROOT:/bin/init.ruxe`;
2. validates that it is a `program` RUXE;
3. copies its payload to the RUXE-declared `load_addr`;
4. writes the `RINI` handoff block;
5. jumps to the RUXE-declared `entry_pc`.

Init starts as the only user-space program. It may use existing MMIO directly for now. That is not a final user-space security model; it is the minimal architecture-preserving step before syscalls exist.

### Memory Contract

The current implementation already uses fixed staging/load regions. ABI v0 should name those regions so future tests stop relying on source-only expectations:

- `0x3F00`: bootloader-to-kernel boot info block.
- `0x3F20`: kernel-to-init handoff info block.
- `0x6000`: kernel-side scratch/staging area for filesystem metadata and loaded file bytes while running `kernel.ruxe`.
- `0x8000`: default user program load address used by program RUXE artifacts.
- `0xA000`: current init RUXE staging address in the example kernel loader.

### Entry Contract

The bootloader validates and enters the kernel. The kernel validates and enters init. Both handoffs use the RUXE `entry_pc` and declared load section metadata.

The kernel does not create a separate process object in ABI v0. Init runs as the only user-space program on the current Rux16 CPU context.

### Exit Contract

ABI v0 treats init halt as system halt:

- normal init halt leaves the machine halted with no automatic reboot;
- init panic or invalid trap is surfaced through the existing control/debug path;
- the kernel does not attempt to restart init in this slice.

Later process supervision can change policy by introducing an explicit process manager ABI. It should not be smuggled into this loader slice.

## Failure Behavior

All failures must be explicit and non-fallback:

- missing ROOT partition: kernel emits `INIT LOAD FAILED` and sets a deterministic panic code;
- invalid ROOT RuxFS: same failure path;
- missing `/bin` or `/bin/init.ruxe`: same failure path;
- init RUXE has wrong ABI kind: same failure path;
- init RUXE has invalid section metadata: same failure path;
- init payload would overlap protected kernel/staging memory: same failure path.
- missing or invalid bootloader-to-kernel boot info: kernel emits `KERNEL BOOT INFO FAILED` and sets a deterministic panic code.

The exact panic codes can be finalized in implementation, but tests should check both visible display/debug text and the code.

## Test Plan

The first implementation plan should add or update tests for:

- kernel loads `ROOT:/bin/init.ruxe` when it is a program RUXE;
- kernel rejects a kernel-profile RUXE at `/bin/init.ruxe`;
- kernel rejects missing `/bin/init.ruxe`;
- bootloader writes the `RKBI` boot info block before entering kernel;
- kernel can read boot info and use `root_start_lba` instead of rediscovering ROOT;
- kernel writes the `RINI` handoff block before entering init;
- init halt leaves the machine halted without reboot fallback.

## Follow-Up Slices

1. Implement bootloader-to-kernel boot info writing and kernel-side reading.
2. Write kernel-to-init `RINI` handoff info before jumping to init.
3. Add negative tests for wrong init ABI kind and missing init.
4. Decide the first syscall/hostcall shape only after init can reliably run as the first user-space program.
5. Later, split reusable RuxFS path reading out of the bootloader/kernel examples if duplication starts blocking changes.

## Non-Goals

- No multitasking.
- No privilege model.
- No scheduler.
- No virtual memory.
- No std::fs API for ordinary programs.
- No fallback init path.
