# Rux BIOS Usable First Stage Implementation Plan

> Issue: [#65](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/65)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Rux BIOS a usable first-stage firmware that reports hardware state and stops at a stable `No bootable device` screen.

**Architecture:** BIOS is ordinary Rux firmware. Host still creates the VM and loads the first image, while BIOS owns guest-visible discovery and boot status. This task does not implement OS boot; it prepares the firmware layer needed by storage boot.

**Tech Stack:** Rux firmware (`.rx`), Rust `rux-compiler` tests, bundled RUXI resources, Kotlin native-runtime/NeoForge tests.

---

## File Structure

- Create `native/rux-compiler/examples/firmware/bios.rx`: BIOS firmware source.
- Modify `native/rux-compiler/tests/rux_runner.rs`: source-level BIOS tests.
- Modify `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/RuxComputerRuntime.kt`: default firmware resource.
- Modify `modules/native-runtime/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/RuxComputerRuntimeFactoryTest.kt`: default firmware test.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RuxFirmwareResourceTest.kt`: bundled BIOS resource and runtime smoke.
- Add generated resource `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/rux-bios.ruxi`.

## Task 1: Add BIOS Source Tests

- [ ] Add failing tests in `native/rux-compiler/tests/rux_runner.rs`:
  - BIOS source compiles and reaches `READY`.
  - BIOS debug output contains `RUX BIOS` and `NO BOOTABLE DEVICE`.
  - BIOS display first lines contain `RUX BIOS` and `No bootable device`.
- [ ] Run `cargo test --test rux_runner bios` from `native/rux-compiler`.
- [ ] Expected: fail because `examples/firmware/bios.rx` does not exist.
- [ ] Commit after green: `test(os): cover rux bios first-stage behavior`.

## Task 2: Implement BIOS Firmware

- [ ] Create `native/rux-compiler/examples/firmware/bios.rx`.
- [ ] Use existing `std::computer`, `std::display`, `std::hardware`, and `std::io` helpers.
- [ ] Set `BOOTING`, write debug/display status, detect storage state conservatively, set `READY`, then idle.
- [ ] Run `cargo test --test rux_runner bios` from `native/rux-compiler`.
- [ ] Expected: pass.
- [ ] Commit: `feat(os): add rux bios firmware`.

## Task 3: Bundle BIOS Resource

- [ ] Build `rux-bios.ruxi` with `./rux emit native/rux-compiler/examples/firmware/bios.rx modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/rux-bios.ruxi`.
- [ ] Add resource existence and smoke tests in `RuxFirmwareResourceTest.kt`.
- [ ] Run focused tests after native library build.
- [ ] Commit: `feat(os): bundle rux bios firmware`.

## Task 4: Make BIOS The Default Firmware

- [ ] Change `RuxComputerRuntimeFactory.DEFAULT_FIRMWARE_RESOURCE` to `firmware/rux-bios.ruxi`.
- [ ] Update `RuxComputerRuntimeFactoryTest`.
- [ ] Keep laptop firmware resource and tests as explicit demo coverage.
- [ ] Run `./gradlew-sandbox :native-runtime:test`.
- [ ] Commit: `feat(os): boot rux bios by default`.

## Task 5: Verification And Issue Update

- [ ] Run `cargo test` in `native/rux-compiler`.
- [ ] Run `cargo test` in `native/rux-vm`.
- [ ] Run `./gradlew-sandbox :native-runtime:test`.
- [ ] Run `./gradlew-sandbox :v1_21_1-neoforge:buildRustVmNativeLibrary`.
- [ ] Run focused `RuxFirmwareResourceTest` with `-Drux.vm.native.library=$PWD/native/rux-vm/target/debug/librux_vm.so`.
- [ ] Update `#65` with implemented behavior and verification evidence.
- [ ] Close `#65` only if all automated checks pass and no manual in-game check remains required.
