# Rux Kernel/Init ABI Design

> Issue: [#117](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/117)

## Context

The current boot chain already reaches user-space code from persistent storage:

`BIOS flash -> BOOT:/boot/loader.ruxe -> ROOT:/boot/kernel.ruxe -> ROOT:/bin/init.ruxe`

The implemented examples prove the mechanics, but the boundary between `kernel.ruxe` and `/bin/init.ruxe` is still an implicit loader convention. This spec defines the first explicit ABI for that boundary. The goal is not to build a full OS yet; the goal is to make the first kernel/user-space handoff precise enough to test and evolve.

## Design Goals

- Keep `kernel.ruxe` and `/bin/init.ruxe` as different ABI roles.
- Keep `/bin/init.ruxe` a normal user-space `program` RUXE.
- Avoid fallback paths. Missing or invalid init must fail explicitly.
- Avoid adding processes, privilege rings, syscalls, or virtual memory in this slice.
- Leave room for those features without changing the basic storage layout.

## Alternatives Considered

### A. Treat init as the kernel

The bootloader could execute `/bin/init.ruxe` directly and skip `kernel.ruxe`. This is smaller, but it removes the kernel boundary before the project has a place to grow OS responsibilities such as device arbitration, error policy, process setup, and future syscall dispatch.

### B. Make init a kernel RUXE

The kernel could load another kernel-profile executable. That keeps the ABI uniform inside privileged code, but it blurs user-space. It would make normal programs and init diverge immediately.

### C. Kernel loads a program RUXE as init

Recommended. `kernel.ruxe` remains the first OS-owned executable. It loads `/bin/init.ruxe`, validates that it is a `program` RUXE, prepares a minimal boot info block, and jumps to init. This keeps the first OS boundary real without adding a full process model yet.

## ABI v0

### Executable Roles

- `BOOT:/boot/loader.ruxe` is a bootloader RUXE.
- `ROOT:/boot/kernel.ruxe` is a kernel RUXE.
- `ROOT:/bin/init.ruxe` is a program RUXE.

The kernel must reject `/bin/init.ruxe` if its RUXE ABI kind is not `program`. It must not retry another path or silently boot another image.

### Storage Contract

The kernel looks for init at exactly:

```text
ROOT:/bin/init.ruxe
```

The first ABI version does not define a search path, initrd, boot menu, or configurable init path. Those can be later kernel policy.

### Memory Contract

The current implementation already uses fixed staging/load regions. ABI v0 should name those regions so future tests stop relying on source-only expectations:

- `0x6000`: kernel-side scratch/staging area for filesystem metadata and loaded file bytes while running `kernel.ruxe`.
- `0x8000`: default user program load address used by program RUXE artifacts.
- `0xA000`: current init RUXE staging address in the example kernel loader.
- `0x7F00`: proposed ABI v0 boot info block address.

The implementation may keep the existing staging address for the first slice, but the spec and tests should treat the boot info block address as the visible contract.

### Boot Info Block

Before jumping to init, the kernel writes a small boot info block at `0x7F00`:

```text
offset  size  field
0x00    4     magic: "RUXI"
0x04    2     version: 1
0x06    2     size_bytes
0x08    4     root_start_lba
0x0C    4     init_ruxe_size_bytes
0x10    4     flags
```

For ABI v0, `flags` is zero. Init receives the boot info address by convention: it may read `0x7F00` directly. A register-passed argument can be added later when the language/CPU ABI has a function-call convention.

### Entry Contract

The kernel validates the RUXE header, copies the init payload to its declared `load_addr`, writes the boot info block, and jumps to the init RUXE `entry_pc`.

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

The exact panic codes can be finalized in implementation, but tests should check both visible display/debug text and the code.

## Test Plan

The first implementation plan should add or update tests for:

- kernel loads `ROOT:/bin/init.ruxe` when it is a program RUXE;
- kernel rejects a kernel-profile RUXE at `/bin/init.ruxe`;
- kernel rejects missing `/bin/init.ruxe`;
- kernel writes the `RUXI` boot info block before entering init;
- init can read boot info and display or debug-print one field;
- init halt leaves the machine halted without reboot fallback.

## Follow-Up Slices

1. Implement boot info block writing and tests in the existing `init_loader.rx` path.
2. Add negative tests for wrong init ABI kind and missing init.
3. Decide the first syscall/hostcall shape only after init can reliably read boot info.
4. Later, split reusable RuxFS path reading out of the bootloader/kernel examples if duplication starts blocking changes.

## Non-Goals

- No multitasking.
- No privilege model.
- No scheduler.
- No virtual memory.
- No std::fs API for ordinary programs.
- No fallback init path.
