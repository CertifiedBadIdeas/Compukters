# Rust BIOS Guest Crate Scaffold

> Issue: [#140](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/140)

**Goal:** Add the dedicated guest-side Rust BIOS crate before wiring it into
the final `.kflash` build.

**Architecture:** `rust/guest/k16-bios` is a freestanding guest executable
crate. It depends on shared `k16-abi` and `k16-rt` crates and owns the Rust BIOS
entrypoint. The crate does not compile through the old Rux source path.

**Tech Stack:** Rust 2021, Cargo workspace, `#![no_std]`, `#![no_main]`,
Gradle build-script convention tests.

## Steps

- [x] Add a failing repository-structure test for `rust/guest/k16-bios`.
- [x] Add `k16-bios` to `rust/guest/Cargo.toml`.
- [x] Add `rust/guest/k16-bios/Cargo.toml`.
- [x] Add `rust/guest/k16-bios/src/main.rs` with the first Rust BIOS
  entrypoint.
- [x] Verify the normal guest workspace and structure checks.

## Follow-Up

The next slice should wire the explicit K16 target build path. Current local
custom rustc exits with an internal compiler error before object emission:
`could not create LLVM TargetMachine for triple: k16`.
