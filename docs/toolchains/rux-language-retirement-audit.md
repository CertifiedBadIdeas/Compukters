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

## Retired Rux-Language Inputs

The active `rust/host/k16-tools` tree no longer owns tracked `.rx` guest source.
The retired source groups were:

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

These groups are no longer part of the active host-tool source tree.

## Compiler And CLI Surface

The retired Rux-language implementation lived in:

- `rust/host/k16-tools/src/frontend/*`: AST, lexer, parser, resolver, and
  diagnostics.
- `rust/host/k16-tools/src/runtime/stdlib.rs`: embedded `.rx` stdlib module
  registry.
- `rust/host/k16-tools/src/bin/rux.rs`: public CLI surface, including
  `rux compile` and related source-driven commands.
- `rust/host/k16-tools/src/advice.rs`: source-level improvement checks for Rux
  listings.

The surviving parts of `rust/host/k16-tools` are host-side machine/tooling
pieces: K16 disassembly, object linking, K16E encoding, volume/filesystem
tooling, inspect/run helpers, and Rust-target smoke support. Guest runtime
source belongs under `rust/guest/k16-rt`.

## Build Integration Status

The old NeoForge Rux-source build integration generated bundled firmware and
storage images from:

- `k16_bios.rx` -> `firmware/k16-bios.kflash`;
- `kernel_loader.rx` -> `kernel-loader.kb`;
- `display_ok.rx` -> `display-ok.kx`.

Those source inputs are not the intended guest software structure. New BIOS,
bootloader, kernel, runtime, and program work should be Rust-owned under
`rust/guest`.

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

These tests were migration blockers. Active host-tool coverage should now use
Rust artifacts, object fixtures, or ABI/tooling tests instead of `.rx` source.

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

The public `rux` command is no longer part of active host tooling. Machine and
artifact commands live under `k16`.

## Retirement State

1. Rux stdlib and source-level advice checks are removed from active tooling.
2. The public `rux compile` and `rux check` CLI surface is removed from active
   tooling.
3. The Rux frontend is removed from active host tooling.
4. Guest-owned Rust work continues under `rust/guest`.

This is a retirement record, not a replacement plan.

## Follow-Up Issues

- [#140](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/140):
  Replace bundled Rux BIOS source with Rust-built firmware.
- [#141](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/141):
  Replace bundled Rux bootloader and kernel sources with Rust artifacts.
- [#143](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/143):
  Convert boot-chain tests away from Rux source dependencies.
- [#142](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/142):
  Remove Rux stdlib and source advice.
- [#144](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/144):
  Retire Rux compiler/frontend and move guest software ownership to
  `rust/guest`.
