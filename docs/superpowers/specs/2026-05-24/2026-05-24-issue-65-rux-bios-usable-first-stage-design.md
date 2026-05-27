# Rux BIOS Usable First Stage Design

> Issue: [#65](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/65)

## Status

Accepted for implementation.

## Context

Rux computers currently boot a bundled RUXI image directly. That is enough for demos, but it makes future storage boot ambiguous: if the host reads an OS image from disk and starts it directly, BIOS policy is skipped.

The BIOS must become a real guest firmware layer before storage boot is implemented. It should run as ordinary Rux code inside the VM, discover hardware through `BootInfo` and `HardwareTable`, report what it found, and stop at a deterministic firmware screen when no bootable device exists.

## Goals

- Run BIOS as the first-stage RUXI firmware for computer/notebook startup.
- Keep host responsibility limited to machine creation, device attachment, BIOS image loading, and CPU startup.
- Let BIOS validate enough boot data to decide whether it can continue.
- Let BIOS discover control/debug/display/storage through guest-visible metadata.
- Treat missing bootable media as a normal firmware state, not as a VM panic.
- Prepare a clear foundation for #62 boot-from-storage handoff.

## Non-Goals

- No OS boot in this issue.
- No filesystem or partition table discovery.
- No multiboot menu.
- No shell.
- No guest-side executable loading from RAM.
- No change to frozen RUXI image ABI v1.

## Runtime State Model

BIOS uses these states:

- `BOOTING`: firmware started and is probing hardware.
- `READY`: BIOS reached a stable status screen, including `No bootable device`.
- `PANIC`: BIOS cannot trust required machine metadata or critical hardware assumptions.

`No bootable device` is not a panic. Real firmware remains alive in this state, usually displaying an error or setup prompt. Rux BIOS should do the same: keep the computer powered on and idle.

## BIOS v1 Flow

1. Set control status to `BOOTING` when control is available.
2. Validate `BootInfo` magic and profile version.
3. Enumerate `HardwareTable`.
4. Locate optional devices:
   - debug output;
   - display0;
   - storage0.
5. Print a debug boot log when debug output exists.
6. Draw a display status screen when display0 exists.
7. Check storage0 media status.
8. If no storage or media is present, show `No bootable device`.
9. Set control status to `READY`.
10. Enter an idle loop.

## Required Output

Debug output should be concise and deterministic:

```text
RUX BIOS
BOOTINFO OK
HW CONTROL OK
HW DEBUG OK
HW DISPLAY0 OK
HW STORAGE0 PRESENT|ABSENT|MISSING|ERROR
NO BOOTABLE DEVICE
```

Display output should be human-readable:

```text
RUX BIOS
BootInfo OK
Display OK
Storage: present|absent|missing|error
No bootable device
```

Exact capitalization may evolve, but tests should pin stable substrings that matter for behavior.

## Storage Handling

For this issue, BIOS only reads storage registers:

- version;
- status;
- error;
- block size;
- capacity;
- media status.

BIOS must not read blocks yet. Actual boot metadata parsing belongs to #62.

## Error Handling

- Invalid `BootInfo` magic or unsupported profile version: set `PANIC` if control exists, write debug if possible, then halt or idle.
- Missing display: continue headless using debug output.
- Missing debug: continue using display output.
- Missing storage: show/report `No bootable device`.
- Storage media absent: show/report `No bootable device`.

## Implementation Shape

- Add `native/rux-compiler/examples/firmware/bios.rx`.
- Add minimal stdlib helpers where they remove duplication or expose storage media status.
- Add compiler runner tests for BIOS debug/display behavior.
- Add bundled firmware resource `firmware/rux-bios.ruxi`.
- Change default Rux computer firmware from `rux-laptop.ruxi` to `rux-bios.ruxi`.
- Keep `rux-laptop.ruxi`, `rux-terminal.ruxi`, and `rux-echo-live.ruxi` as demos/dev fixtures.

## Compatibility

Existing firmware resources remain available. The default computer firmware changes to BIOS, but test/demo firmware can still be loaded explicitly.

## Verification

- `cargo test` in `native/rux-compiler`.
- `cargo test` in `native/rux-vm`.
- `./gradlew-sandbox :native-runtime:test`.
- `./gradlew-sandbox :v1_21_1-neoforge:buildRustVmNativeLibrary`.
- Focused `RuxFirmwareResourceTest` with `-Drux.vm.native.library=...`.
