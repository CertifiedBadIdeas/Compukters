# Rux Language Retirement Audit

> Issue: [#139](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/139)
>
> Strategy: [K16 Rust-First Language Strategy](k16-language-strategy.md)

## Decision Boundary

The retirement target is the Rux language stack: `.rx` source files, the Rux
frontend, Rux stdlib modules, Rux-authored firmware/kernel/init examples, and
CLI behavior whose purpose is compiling Rux source.

This audit does not rename the Rux language. `.rx`, the Rux frontend, Rux
stdlib modules, and Rux-language commands remain named Rux while they exist.

The machine/tooling naming decision now lives in
[#147](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/147):
future VM, ISA, artifact, storage, filesystem, and Rust target names should use
`Kraft16`/`K16`. Current ABI documents may still describe existing Rux-named
formats until each compatibility-affecting ABI migration lands.

## Current Rux-Language Inputs

Tracked `.rx` source is concentrated under `rust/host/k16-tools`:

- `examples/firmware/*.rx`: BIOS and old firmware demos, including
  `k16_bios.rx`.
- `examples/boot/*.rx`: bootloader experiments, including
  `kernel_loader.rx`.
- `examples/kernel/*.rx`: kernel examples, including `display_ok.rx` and
  `init_loader.rx`.
- `examples/init/*.rx`: init examples, including `rini_init.rx` and
  `trap_init.rx`.
- `stdlib/rux/abi/**/*.rx`: low-level ABI wrapper modules.
- `stdlib/std/*.rx`: higher-level Rux stdlib modules.

These are all retirement targets. They may remain temporarily only while they
are still the only working boot-chain fixtures.

## Compiler And CLI Surface

The Rux-language implementation currently lives in:

- `rust/host/k16-tools/src/frontend/*`: AST, lexer, parser, resolver, and
  diagnostics.
- `rust/host/k16-tools/src/runtime/stdlib.rs`: embedded `.rx` stdlib module
  registry.
- `rust/host/k16-tools/src/bin/rux.rs`: public CLI surface, including
  `rux compile` and related source-driven commands.
- `rust/host/k16-tools/src/advice.rs`: source-level improvement checks for Rux
  listings.

The parts of `rust/host/k16-tools` that should survive are the machine/tooling
pieces: K16 object assembly/disassembly, object linking, K16E encoding,
volume/filesystem tooling, inspect/run helpers, and Rust-target smoke support.
Those may need a later crate/CLI rename, but they are not language frontend
features.

## Build Integration Blockers

The NeoForge module still generates bundled firmware and storage images from
Rux source:

- `k16_bios.rx` -> `firmware/k16-bios.kflash`;
- `kernel_loader.rx` -> `kernel-loader.kb`;
- `display_ok.rx` -> `display-ok.kx`.

This is wired in
`modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts` through `cargo run --bin
rux -- compile`. The first practical migration target is to replace these
inputs with Rust-built or object-level artifacts so Gradle no longer depends
on `.rx` source for the bundled boot chain.

## Test Blockers

The highest-impact Rux-language test dependencies are:

- `rust/host/k16-tools/tests/k16_artifact_backend.rs`: compiles and executes
  bundled BIOS source and verifies many BIOS behaviors.
- `rust/host/k16-tools/tests/rux_volume_cli.rs`: relies on
  `kernel_loader.rx`, `init_loader.rx`, `rini_init.rx`, and `trap_init.rx` for
  boot-chain, K16FS, kernel/init, and syscall-style tests.
- `rust/host/k16-tools/tests/rux_compile_cli.rs`: directly tests
  `rux compile` from `.rx` source.
- `rust/host/k16-tools/tests/rux_check_cli.rs`: tests source-level Rux code
  advice.
- `rust/host/k16-tools/tests/rux_public_cli_surface.rs`: expects public help
  text to expose `rux compile`.

These tests should not be deleted first. They should be replaced with Rust
artifact, object fixture, or ABI/tooling tests as each dependency is migrated.

## Keep For Now

These layers remain part of the active Rust-first toolchain path. They should
move toward Kraft16/K16 names as #147 implementation slices land:

- Kraft16 CPU and VM execution;
- ELF32 Kraft16 object ABI;
- K16 executable container, currently represented by the existing `K16E`
  format;
- object linker and relocation handling;
- `k16 run`, `k16 inspect`, `k16 disasm`, and volume/filesystem tooling where
  they operate on machine artifacts rather than Rux source;
- LLVM, Clang, and Rust no_core smoke tooling.

The command name `rux` remains correct for Rux-language behavior such as
`rux compile` and `rux check`. Machine/artifact commands should move to `k16`
when their slices are renamed.

## Replacement Order

1. Replace bundled BIOS generation with Rust-built or object-level firmware.
2. Replace bundled bootloader and kernel examples with Rust-built artifacts.
3. Convert boot-chain tests from `.rx` source assertions to artifact/ABI
   assertions.
4. Remove Rux stdlib and source-level advice checks.
5. Remove `rux compile` public CLI behavior.
6. Remove the Rux frontend once no tracked tests or build tasks depend on it.

This order preserves the current working boot pipeline while moving every
guest-code source artifact toward Rust.

## Follow-Up Issues

- [#140](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/140):
  Replace bundled Rux BIOS source with Rust-built firmware.
- [#141](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/141):
  Replace bundled Rux bootloader and kernel sources with Rust artifacts.
- [#143](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/143):
  Convert boot-chain tests away from Rux source dependencies.
- [#142](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/142):
  Remove Rux stdlib and source advice after artifact replacements.
- [#144](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/144):
  Remove `rux compile` and Rux frontend after Rust replacements.
