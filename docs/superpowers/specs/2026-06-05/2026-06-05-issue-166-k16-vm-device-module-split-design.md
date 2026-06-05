# K16 VM Device Module Split Design

> Issue: [#166](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/166)

## Context

`rust/host/k16-vm/src/computer/devices.rs` currently contains every computer MMIO device implementation in one large file: BIOS flash, control registers, debug serial, serial input, text display, framebuffer, and storage. The file is readable in local chunks, but it is too large to navigate as the VM grows.

Recent work clarified the CPU execution path. This slice applies the same readability direction to the device side without changing guest-visible behavior.

## Goals

- Split device implementations by device family.
- Keep existing type names and crate-visible APIs stable.
- Keep `computer::devices` as the import surface for `machine.rs`, `profile.rs`, snapshots, JNI, and tests.
- Make `.agents/tmp/` an ignored local scratch area, not a tracked archive.

## Non-Goals

- No MMIO address, register, or ABI changes.
- No storage/display/framebuffer behavior changes.
- No benchmark history or archived agent files in the repository.
- No split of `computer/machine.rs` in this slice.

## Design

`computer/devices.rs` becomes a small module index and re-export layer. Device-family implementations move into sibling files under `computer/devices/`:

- `bios.rs` for `BiosFlashDevice`.
- `control.rs` for `ComputerControlDevice`.
- `serial.rs` for `DebugSerialDevice` and `SerialInputDevice`.
- `text_display.rs` for `ComputerTextDisplaySnapshot` and `TextDisplayDevice`.
- `framebuffer.rs` for `FramebufferDevice`.
- `storage.rs` for `StorageMedia`, storage media backends, and `StoragePortDevice`.

All moved items preserve their current visibility and method names. Existing callers continue importing from `crate::computer::devices::{...}`.

## Verification

- `cd rust/host/k16-vm && cargo fmt -- --check`
- `cd rust/host/k16-vm && cargo test`
- `git diff --check`
