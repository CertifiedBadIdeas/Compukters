# Rust BIOS Project Design
> Issue: [#140](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/140)

## Context

The bundled K16 BIOS is still authored as Rux source at
`rust/host/k16-tools/examples/firmware/k16_bios.rx` and Gradle builds
`firmware/k16-bios.kflash` through `rux compile --target bios`.

That keeps the first firmware stage tied to the legacy Rux frontend. The
project direction is Rust-first guest software, so BIOS should become the first
guest Rust project. Bootloader and kernel should follow the same organization,
but they are separate implementation slices.

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

`rust/guest/k16-bios` now exists as the dedicated Rust BIOS guest crate. Its
entrypoint is a `#![no_std]`, `#![no_main]` `_start` path that writes the same
initial BIOS/no-bootable-device text through the shared `k16-abi` MMIO surface.

The crate's executable target is gated behind an explicit `k16-target` feature
so normal host-side workspace checks do not try to link freestanding BIOS code
against the host runtime. The final `k16-target` build path must use the custom
K16 Rust target pipeline. On the current local toolchain, that path is blocked
before object emission by rustc failing to create an LLVM TargetMachine for
`k16`; this should stay visible instead of being hidden behind a Rux fallback.

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

Gradle should stop declaring `rust/host/k16-tools/examples/firmware/k16_bios.rx`
as the bundled BIOS input. The BIOS task should depend on the Rust BIOS project
and on the K16 packaging toolchain needed to produce `.kflash`.

The first implementation may use the minimum Rust path the current toolchain can
support, but it must be visibly the Rust BIOS path. If the custom Rust target is
not yet capable of producing the final artifact directly, the blocker should be
made explicit rather than hidden behind a Rux fallback.

## Follow-Up Slices

- #141 should apply the same guest workspace model to bootloader and kernel.
- #143 should convert boot-chain tests away from Rux source assertions once the
  Rust BIOS/boot/kernel artifacts exist.
- #144 remains the later cleanup for removing the Rux frontend after Rust
  replacements cover the boot chain.

## Verification

The implementation slice should verify:

- `cargo test --manifest-path rust/host/k16-tools/Cargo.toml --test k16_artifact_backend`
- `./gradlew-sandbox :v1_21_1-neoforge:processResources`
- the generated `firmware/k16-bios.kflash` comes from the Rust BIOS project,
  not from `rux compile`.
