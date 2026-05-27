# Rux Rust-Owned Storage Backend Implementation Plan

> Issue: [#64](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/64)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move normal `storage0` media I/O to a Rust-owned `.ruxvol` file backend while Kotlin continues to provide the concrete world-save path.

**Architecture:** Add a Rust `StorageMedia` abstraction with in-memory and file-backed implementations, then wire JNI/Kotlin runtime creation to pass `storage0.ruxvol` paths for Minecraft computers. Keep the guest storage MMIO ABI unchanged.

**Tech Stack:** Rust `std::fs::File`, Kotlin/JNI, Gradle `./gradlew-sandbox`, Rust `cargo test`.

---

## File Structure

- Modify `native/rux-vm/src/computer/devices.rs`: make storage MMIO depend on a media abstraction instead of directly owning a `Vec<u8>`.
- Modify or create Rust storage module under `native/rux-vm/src/computer/`: implement in-memory and `.ruxvol` file-backed media.
- Modify `native/rux-vm/src/computer/profile.rs`: add profile creation from `storage0Path`.
- Modify `native/rux-vm/src/computer/handle.rs`: add handle creation path for file-backed storage.
- Modify `native/rux-vm/src/jni.rs`: accept nullable `storage0Path` from Kotlin.
- Modify `modules/native-runtime/src/main/kotlin/.../NativeVmBindings.kt`: pass path to native creation.
- Modify `modules/native-runtime/src/main/kotlin/.../RuxComputerRuntime.kt`: expose runtime factory parameter for `storage0Path`.
- Modify `modules/v1_21_1/.../ComputerRuntimeDeviceFactory.kt`: create/open volume on Kotlin side and pass its path, not payload bytes.
- Add focused tests in Rust and Kotlin.

## Task 1: Rust Storage Media Abstraction

- [x] Add a failing Rust test proving `StoragePortDevice` can read/write/flush through a non-`Vec` media backend.
- [x] Run `cargo test -p rux-vm <test-name>` and confirm it fails because the abstraction is missing.
- [x] Add `StorageMedia` and `InMemoryStorageMedia`.
- [x] Update `StoragePortDevice` to use the abstraction.
- [x] Run `cargo test` in `native/rux-vm`.
- [x] Commit: `refactor(vm): abstract rux storage media backend`.

## Task 2: Rust RUXVOL File Backend

- [x] Add failing Rust tests for opening a `.ruxvol`, block read/write persistence, flush, invalid magic, unsupported version, and truncated payload.
- [x] Run the focused tests and confirm expected failures.
- [x] Implement `RuxVolumeFileStorageMedia`.
- [x] Add handle/profile creation from a storage0 file path.
- [x] Run `cargo test` in `native/rux-vm`.
- [x] Commit: `feat(vm): add rux volume file storage backend`.

## Task 3: Kotlin/JNI Path Wiring

- [x] Add failing Kotlin/JNI-facing tests or compile checks for `storage0Path` creation arguments.
- [x] Run `./gradlew-sandbox :native-runtime:test` and confirm expected failure.
- [x] Update `NativeVmBindings`, `RuxComputerRuntimeFactory`, and JNI signatures.
- [x] Update `ComputerRuntimeDeviceFactory` to pass `RuxVolumeBlob.path` or an equivalent concrete file path instead of payload bytes.
- [x] Keep snapshot-based creation available for tests/non-Minecraft runtime paths if still needed.
- [x] Run `./gradlew-sandbox :native-runtime:test` and `./gradlew-sandbox :v1_21_1-common:test`.
- [x] Commit: `feat(runtime): pass rux storage volume path to native vm`.

## Task 4: Integrated Verification And Roadmap Update

- [x] Run `cargo test` in `native/rux-vm`.
- [x] Run `./gradlew-sandbox :native-runtime:test`.
- [x] Run `./gradlew-sandbox :v1_21_1-common:test`.
- [x] If native signature is exercised by NeoForge tests, run the focused JNI smoke with `-Drux.vm.native.library=...`.
- [x] Update issue #64 with implemented behavior, verification commands, and any remaining limitations.
- [x] Close #64 only if all acceptance criteria are verified; otherwise leave it open with exact remaining work.
