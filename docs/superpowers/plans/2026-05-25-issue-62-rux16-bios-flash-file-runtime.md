# Rux16 BIOS Flash File Runtime Implementation Plan

> Issue: [#62](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/62)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Start in-game Rux computers from a per-computer BIOS flash file loaded by Rust, not from JVM-provided program bytes.

**Architecture:** Add a JVM workspace preparer that writes `bios.flash` from a bundled resource, add a JNI entrypoint that accepts `biosFlashPath` and `storage0Path`, and make Rust read the BIOS flash file before creating the Rux16 BIOS flash computer. Update the in-game factory to use the new file-backed path.

**Tech Stack:** Kotlin/JVM, JNI, Rust 2021, Rux16 BIOS flash, path-backed `.ruxvol`, Gradle tests.

---

## File Structure

- Create `docs/superpowers/specs/2026-05-25-issue-62-rux16-bios-flash-file-runtime-design.md`: design record.
- Create `docs/superpowers/plans/2026-05-25-issue-62-rux16-bios-flash-file-runtime.md`: this plan.
- Create `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/RuxBiosFlashWorkspace.kt`: JVM-side workspace preparation from resources.
- Modify `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`: add file-backed BIOS flash native creation and Rux16 run binding.
- Modify `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/RuxComputerRuntime.kt`: add `createFromBiosFlash`.
- Modify `native/rux-vm/src/computer/handle.rs`: add BIOS flash path loader.
- Modify `native/rux-vm/src/jni.rs`: add JNI creation/run entrypoints for Rux16 BIOS flash.
- Modify `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerRuntimeDeviceFactory.kt`: prepare workspace and call `createFromBiosFlash`.
- Add generated `firmware/rux16-bios.flash`: bundled default raw BIOS flash compiled from source.
- Add or update tests in native-runtime, native Rust, and v1_21_1 modules.

## Task 1: Documentation

- [ ] Save this spec and plan.
- [ ] Run `git diff --check -- docs/superpowers/specs/2026-05-25-issue-62-rux16-bios-flash-file-runtime-design.md docs/superpowers/plans/2026-05-25-issue-62-rux16-bios-flash-file-runtime.md`.
- [ ] Commit with `docs(vm): plan Rux16 BIOS flash file runtime`.

## Task 2: TDD And Implementation

- [ ] Write failing tests for workspace preparation, source wiring, native path creation, and JNI method shape.
- [ ] Add the JVM workspace preparer and bundled BIOS flash resource.
- [ ] Add Rust path-based BIOS flash creation and JNI entrypoints.
- [ ] Update Kotlin runtime and in-game factory to use path-backed BIOS flash startup.

## Task 3: Verification And Commit

- [ ] Run focused native Rust tests for Rux computer path loading.
- [ ] Run focused Kotlin tests for native-runtime and v1_21_1 wiring.
- [ ] Run `git diff --check`.
- [ ] Commit with `feat(vm): start Rux computers from BIOS flash file`.
