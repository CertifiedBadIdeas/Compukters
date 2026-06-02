# Rust BIOS Project Design
> Issue: [#140](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/140)

## Context

The bundled K16 BIOS used to be authored as Rux source at
`rust/host/k16-tools/examples/firmware/k16_bios.rx` and built into
`firmware/k16-bios.kflash` through the retired Rux compiler path.

That tied the first firmware stage to the legacy Rux frontend. The accepted
project direction is Rust-first guest software, with BIOS, bootloader, and
kernel owned by crates under `rust/guest`.

## Design Options

### Option A: Keep Guest Rust Crates Beside Host Crates

This would put BIOS beside `rust/host/k16-vm` and `rust/host/k16-tools`. It is
simple but mixes host-side native code with guest-side software. That boundary
will become confusing once BIOS, bootloader, kernel, and user programs all
exist.

### Option B: Single Shared Firmware Crate

This would put BIOS, bootloader, and kernel entrypoints in one crate. It reduces
initial files but forces different boot contracts, memory layouts, and artifact
types into one package. BIOS flash, boot artifacts, and kernel executables are
not the same kind of program.

### Option C: Guest Workspace With Separate Crates

Create a guest-software workspace, for example:

```text
rust/
  guest/
    Cargo.toml
    k16-bios/
    k16-bootloader/
    kraft-kernel/
```

Each crate owns one guest program class and produces exactly one primary K16
artifact type. Shared guest support can later move into explicit support crates
under the same workspace.

This is the preferred direction.

## Decision

Use a dedicated guest Rust workspace with separate crates for BIOS, bootloader,
and kernel. Issue #140 implements only the BIOS part:

```text
rust/guest/k16-bios
  -> Rust-authored BIOS source
  -> K16 object/link/package path
  -> firmware/k16-bios.kflash
```

The old `.rx` BIOS must not remain the source of truth. It may stay temporarily
as a legacy comparison fixture only if a test explicitly labels it that way.

## Current Implementation State

`rust/guest/k16-bios` is the dedicated Rust BIOS guest crate. Its entrypoint is
a `#![no_std]`, `#![no_main]` `_start` path that writes the same initial
BIOS/no-bootable-device text through the shared `k16-abi` MMIO surface.

NeoForge resource generation now depends on the shared Gradle
`prepareK16Toolchain` path, resolves the prepared K16 toolchain, and builds the
BIOS as a Rust `bin` crate through `k16-ld --k16-target=bios`. The resulting
Cargo-linked artifact is copied directly to `firmware/k16-bios.kflash`.

The prepared toolchain may be a published prebuilt archive, an explicit
`k16ToolchainDir`, or a source-built staged install from
`prepareBuiltK16Toolchain`. Missing prepared toolchain state is a hard build
error. There is no fallback to host rustc, host linker behavior, or the old Rux
BIOS source path.

## BIOS Contract

The Rust BIOS must preserve the current observable behavior:

- it starts from BIOS flash, not from storage;
- it writes `K16 BIOS` to debug/display output;
- when storage0 has no valid boot path, it reports `No bootable device`;
- when storage0 has a valid K16 volume with a boot artifact, it follows the
  existing boot handoff contract.

Missing Rust build inputs or missing generated artifacts should be hard build
errors. There should be no fallback to the old Rux BIOS path.

## Build Integration

Gradle no longer declares
`rust/host/k16-tools/examples/firmware/k16_bios.rx` as a bundled BIOS input.
The BIOS task depends on:

- the Rust BIOS crate under `rust/guest/k16-bios`;
- the K16 target spec at `tools/k16-unknown-kraftos.json`;
- the prepared K16 toolchain from `prepareK16Toolchain`;
- the K16 host linker driver `k16-ld`.

The BIOS build uses Cargo directly with `-Zbuild-std=core`, `RUSTC` pointed at
the prepared K16 rustc, and explicit K16 linker flags. The selected firmware
profile controls the Cargo profile and artifact lookup. There is no debug
artifact fallback when release firmware is selected.

## Follow-Up Slices

- #141 tracks remaining bootloader/kernel artifact cleanup if any old assertions
  survive outside the active Gradle resource path.
- #143 should convert any remaining boot-chain tests away from Rux source
  assertions.
- #144 remains the later cleanup for removing the Rux frontend after Rust
  replacements cover the boot chain.

## Verification

The implementation slice should verify:

- `./gradlew-sandbox :v1_21_1-neoforge:processResources -Pk16ToolchainMode=prebuilt -Pk16ToolchainDir=<prepared-toolchain>`
- `./gradlew-sandbox :v1_21_1-neoforge:test --tests '*K16FirmwareResourceTest.bundledK16BiosFlashShowsNoBootableDeviceWithBundledSystemStorage0Volume' -Pk16ToolchainMode=prebuilt -Pk16ToolchainDir=<prepared-toolchain>`
- the generated `firmware/k16-bios.kflash` comes from the Rust BIOS project,
  not from `rux compile`.
