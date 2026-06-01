# K16 Rust core Sysroot

> Issue: [#148](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/148)

## Goal

K16 guest software should move from Rust `#![no_core]` proofs to ordinary
freestanding `#![no_std]` Rust backed by `core`. This is the required baseline
for BIOS, bootloader, kernel, and later user-space code written in Rust.

## Design

The first core milestone is a strict smoke path, not `alloc` or hosted `std`.
`tools/k16-rust-core-smoke.sh` uses nightly Cargo with `-Z build-std=core`,
the K16 Rust target JSON, and the custom K16 rustc to build `core` and compile a
tiny `#![no_std]` program. The object then goes through the existing K16 object
pipeline:

```text
cargo rustc -Z build-std=core -Z json-target-spec -- -Cjump-tables=no
  -> K16 ELF32 object
  -> k16 runtime k16-startup + k16-memory-helpers
  -> k16 link --target program
  -> k16 run
```

The firmware Gradle path should use the same runtime tier for BIOS, bootloader,
and kernel object builds:

```text
-Zbuild-std=core -Zjson-target-spec -- -Cjump-tables=no
```

No `alloc`, no `std`, no unwinding, and no hidden host fallback are part of this
slice. Missing K16 target support or missing helper symbols must remain hard
build or link failures.

The strict core smoke path passes with the aligned local K16 stage1 Rust
toolchain. It proves the current baseline: `core` can be built for K16, a
`#![no_std]` object can be emitted, and the linked `K16E` program can return
through the VM run path.

## Boundaries

`core` is allowed for BIOS and bootloader because it does not require an OS or
heap. `alloc` is excluded until K16 has an explicit allocator boundary. Hosted
`std` is excluded until the guest OS owns filesystem, time, environment,
synchronization, and I/O services.

## Verification

- `cargo test --manifest-path rust/host/k16-tools/Cargo.toml rust_nocore_smoke_artifacts_are_documented_and_strict`
- `./gradlew-sandbox :build-scripts:test --tests '*K16ToolingRenameTest.rustFirmwareGradleBuildsCoreOnlyArtifacts'`
- `tools/k16-rust-core-smoke.sh` with a custom K16 rustc/Cargo toolchain.
