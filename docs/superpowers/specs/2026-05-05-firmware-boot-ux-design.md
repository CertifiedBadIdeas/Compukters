# Firmware Boot UX Design

Date: 2026-05-05

## Problem

The current startup path can fail before the user sees any terminal output. Boot compilation errors are visible in logs
as VM crash state, but the terminal UI only derives its state from power state plus received terminal bytes. If startup
fails quickly, pending stdout can also be lost before the next server tick flush. The result is a black or unhelpful
terminal screen: the computer appears not to start, but the player cannot see why.

## Goals

- Make startup failures visible to the user in the terminal, not only in server logs.
- Model boot like a real computer: firmware starts first, then launches a user startup file.
- Keep the VM powered on when the user startup file is missing or broken.
- Rename the user startup file to `boot.ck` with no compatibility migration from the old user `bios.ck` startup file.
- Reserve `bios.ck` for true firmware stored outside the normal user filesystem.
- Keep the terminal hidden or inactive when the computer is actually powered off.

## Terminology

- `bios.ck`: firmware code. It belongs to a hidden firmware partition, not to the normal user filesystem.
- `boot.ck`: user startup program. It belongs to the normal device filesystem and is the first user-controlled program
  launched by firmware.
- Firmware partition: hidden per-device storage for firmware. Initially it may be backed by the mod's default ROM
  `bios.ck`; later it can be exposed through a programmer item/block for read/write flashing.

## Startup Model

The host always boots the device VM with firmware `bios.ck`. The host no longer treats the user startup file as the VM
entrypoint.

Startup flow:

1. The player turns on the computer.
2. The host creates a VM and runs firmware `bios.ck` from the firmware partition or default ROM fallback.
3. Firmware writes boot progress to stdout.
4. Firmware checks for `boot.ck` in the normal filesystem.
5. If `boot.ck` exists, firmware launches it through `process::run("boot.ck")`.
6. Firmware reports the result and remains alive until the player shuts down or reboots the computer.

If `boot.ck` is missing, invalid, or crashes, the VM stays powered on and the terminal shows a readable diagnostic. This
is not a VM boot failure; it is a user startup failure handled by firmware.

## Firmware Storage

Firmware must not be a normal user file. Ordinary filesystem APIs and shell tools should not list, edit, or delete
`bios.ck` by accident.

Initial implementation may use a read-only firmware provider that always returns the bundled default `bios.ck`. The data
model should leave room for a per-device firmware partition so a future programmer tool can reflash it. A broken custom
firmware is allowed to brick normal boot, but it should be recoverable through the programmer.

## Process API Contract

For the first implementation, `process::run(path)` is enough. It should not throw compile or runtime failures from the
child program into firmware. Instead it should:

- print `Program not found: <path>` and return non-zero for missing files;
- print `Compilation Error in <path>: ...` and return non-zero for child compile diagnostics;
- print `Program error in <path>: ...` and return non-zero for child runtime failures;
- return `0` on success.

This keeps `bios.ck` simple while preserving a future path to a structured `process::runResult(path)` API.

## Error Model

There are two error levels.

### Firmware-level failure

Examples: missing firmware, broken firmware partition, invalid `bios.ck`.

The VM cannot rely on CK stdout if firmware itself does not start. The host/UI should surface this as a device-level
BIOS/Firmware error. This is separate from normal user startup errors and is recoverable later through firmware reflash
tooling.

### User boot failure

Examples: missing `boot.ck`, syntax or semantic diagnostics, missing `pub fun main()`, runtime exception, non-zero exit.

Firmware prints the diagnostic in the terminal and stays alive. The user can edit `boot.ck` and reboot, or shut down the
computer.

## Terminal and Flush Requirements

Stdout is the primary diagnostic channel for firmware boot. It must be reliable even when a child program fails quickly.

Required behavior:

- pending stdout bytes must not be lost when a child program exits or fails quickly;
- final stdout flush should happen on terminal-state transitions, detach, and crash paths, not only on regular server
  ticks while a live VM handle exists;
- user `boot.ck` failures should normally be displayed by the still-running firmware, but final flush remains a safety
  net;
- the terminal UI can remain `PoweredOff`, `Connecting`, and `Active` for the first version because user boot failure is
  terminal text, not a terminal-state crash.

## Migration

- New user startup file: `boot.ck` only.
- Old user `bios.ck` startup files are not migrated.
- Workspace initialization should create or ship `boot.ck`, `shell.ck`, and utility programs in the user filesystem.
- Firmware `bios.ck` is stored in the hidden firmware partition / ROM provider.

## Testing Strategy

- Runtime tests for `process::run("missing.ck")`: returns non-zero and prints a clear message.
- Runtime tests for `process::run("boot.ck")` with compile diagnostics: returns non-zero, prints diagnostics, and does
  not crash the caller.
- Background VM tests for missing `boot.ck`: VM remains active and terminal contains a missing-file diagnostic.
- Background VM tests for invalid `boot.ck`: VM remains active and terminal contains the compile diagnostic.
- Background VM tests for successful `boot.ck`: firmware runs it, reports result, and does not power off by itself.
- Flush regression tests: fast stdout before exit/failure reaches attached terminal sessions.
- ROM compile tests: true firmware `bios.ck` compiles, and default user programs including `boot.ck` and `shell.ck`
  compile.

## Acceptance Criteria

- Turning on a computer always produces a visible outcome: firmware boot log, missing `boot.ck`, compile error, runtime
  error, successful boot result, or device-level firmware error.
- A broken or missing `boot.ck` never leaves the user with an unexplained black terminal.
- A broken or missing `boot.ck` does not power off the VM automatically.
- `bios.ck` is no longer a normal user startup file; `boot.ck` is the user startup file.
- Powered-off computers still do not show an active terminal surface.