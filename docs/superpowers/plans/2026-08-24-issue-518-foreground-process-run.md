# Foreground Process Run Implementation Plan

> Issue: [#518](https://github.com/CertifiedBadIdeas/Compukters/issues/518)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Boot persistent computers from extensionless `/rom/boot` and execute `/rom/shell` and `/home` binaries through one bounded Rust-owned foreground `process.run` stack.

**Architecture:** `ComputerMachine` replaces its single `Session` with a bounded stack of independent process frames while retaining one machine-owned terminal and filesystem. A trusted K2 intrinsic lowers `process.run(String, Int): Int` to an internal asynchronous capability; Rust resolves, verifies, admits, and runs the child without crossing FFM. Minecraft opens the ROM boot entry instead of passing shell bytes directly.

**Tech Stack:** Rust 2024, Compukter Artifact v1 verifier/interpreter, Rust VFS, C ABI plus JDK 25 FFM, Kotlin 2.3/K2 IR, Gradle 9.7, NeoForge 26.1 GameTest.

---

## File Structure

- `host/compukter-vm/src/process.rs` — public process masks, limits, stable results, and crate-private process frame/reservation types.
- `host/compukter-vm/src/computer.rs` — foreground stack orchestration and internal terminal/filesystem/process request handling.
- `host/compukter-vm/src/filesystem/tree.rs` — bounded executable read operation that checks metadata and execute authority.
- `host/compukter-vm/src/execution/fixtures.rs` — deterministic parent/child artifacts for process tests and GameTest seeding.
- `host/compukter-ffi/src/{bridge.rs,ffi_api.rs,lib.rs}` — boot-from-ROM machine construction and exported ABI symbol.
- `modules/native-runtime/.../{LowLevelVmBridge.kt,FfmBridge.kt,VmSession.kt}` — FFM boot entry and Kotlin session factory.
- `modules/compiler-k2/src/main/resources/compukter-api/process.kt` — trusted no-std process source API.
- `modules/compiler-k2/.../TrustedIntrinsicRegistry.kt` — trusted process intrinsic identity and lowering contract.
- `system/programs/{boot.kt,shell.kt}` — ordinary boot and shell programs.
- `modules/compiler-k2/build.gradle.kts` — deterministic boot and shell artifact generation.
- `modules/v26_1/v26_1-common/.../{SystemProgramImage.kt,SystemRomImage.kt}` — extensionless resource loading and canonical two-entry ROM.
- `modules/core/...` and `modules/v26_1/v26_1-common/.../ComputerBlockEntity.kt` — production boot-mode boundary and removal of installed-artifact bootstrap.
- Existing Rust, compiler, FFM, core, Minecraft, and GameTest suites — behavioral coverage at every boundary.

### Task 1: Process ABI and Executable VFS Admission

**Files:**
- Create: `host/compukter-vm/src/process.rs`
- Modify: `host/compukter-vm/src/lib.rs`
- Modify: `host/compukter-vm/src/filesystem/tree.rs`
- Test: `host/compukter-vm/tests/filesystem_memory.rs`
- Test: `host/compukter-vm/src/process.rs`

- [ ] **Step 1: Write failing tests for stable process values and executable reads**

Add table-driven assertions for the exact `ProcessResult` codes `0..=16`, mask subset validation, invalid `ProcessLimits`, and a VFS test proving that executable bytes require `EXECUTE`, reject directories/non-executable files, and remain readable from immutable `/rom`.

```rust
assert_eq!(ProcessResult::Exited.code(), 0);
assert_eq!(ProcessResult::IoFailed.code(), 16);
assert!(ProcessCapabilityMask::new(0b111).unwrap().allows(0b011));
assert_eq!(filesystem.read_executable(&reader, &path("/home/tool")), Err(FileSystemError::PermissionDenied));
assert_eq!(filesystem.read_executable(&executor, &path("/home/data")), Err(FileSystemError::NotExecutable));
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
cargo test --manifest-path host/compukter-vm/Cargo.toml process::tests
cargo test --manifest-path host/compukter-vm/Cargo.toml --test filesystem_memory
```

Expected: compile failure because `ProcessResult`, `ProcessCapabilityMask`, `ProcessLimits`, `FileSystemError::NotExecutable`, and `read_executable` do not exist.

- [ ] **Step 3: Add the minimal public process contract and VFS operation**

Define the exact stable result enum and checked masks/limits:

```rust
#[repr(i32)]
pub enum ProcessResult {
    Exited = 0,
    InvalidCapabilities = 1,
    DepthLimit = 2,
    StartLimit = 3,
    InvalidPath = 4,
    NotFound = 5,
    PermissionDenied = 6,
    NotExecutable = 7,
    InvalidArtifact = 8,
    AdmissionFailed = 9,
    StartFailed = 10,
    AllocationExhausted = 11,
    QuotaExhausted = 12,
    Trapped = 13,
    Faulted = 14,
    HostFailed = 15,
    IoFailed = 16,
}

pub struct ProcessLimits {
    pub maximum_depth: u32,
    pub maximum_starts: u64,
    pub maximum_aggregate_heap_bytes: u64,
    pub maximum_aggregate_frame_storage_bytes: u64,
}
```

`ComputerFileSystem::read_executable` must require `INSPECT | READ | EXECUTE`, require file metadata with `executable == true`, bound the allocation by `maximum_file_bytes`, copy the immutable object bytes, and close its internal handle on every return path.

- [ ] **Step 4: Run focused and crate tests GREEN**

Run:

```bash
cargo test --manifest-path host/compukter-vm/Cargo.toml process::tests
cargo test --manifest-path host/compukter-vm/Cargo.toml --test filesystem_memory
cargo fmt --manifest-path host/compukter-vm/Cargo.toml --check
```

Expected: all selected tests pass and formatting is clean.

- [ ] **Step 5: Commit the VM contract**

```bash
git -C host/compukter-vm add src/process.rs src/lib.rs src/filesystem/tree.rs tests/filesystem_memory.rs
git -C host/compukter-vm commit -m "feat(process): define bounded executable admission (#518)"
```

### Task 2: Foreground Session Stack

**Files:**
- Modify: `host/compukter-vm/src/process.rs`
- Modify: `host/compukter-vm/src/computer.rs`
- Modify: `host/compukter-vm/src/execution/fixtures.rs`
- Test: `host/compukter-vm/src/computer.rs`

- [ ] **Step 1: Write failing stack lifecycle tests**

Build deterministic fixtures for a parent async process call and child normal halt. Assert that the parent request remains suspended, only the child advances, the child pop resumes the exact parent request with `ProcessResult::Exited`, and a second nested run reaches depth three.

```rust
assert_eq!(computer.process_depth(), 1);
assert_eq!(computer.advance(64, 0).unwrap(), ComputerAdvanceOutcome::SliceExhausted);
assert_eq!(computer.process_depth(), 2);
assert_eq!(computer.advance(64, 0).unwrap(), ComputerAdvanceOutcome::SliceExhausted);
assert_eq!(computer.process_depth(), 1);
assert_eq!(advance_until_halted(&mut computer), Some(ComputerValue::I32(0)));
```

- [ ] **Step 2: Run the lifecycle test and verify RED**

Run:

```bash
cargo test --manifest-path host/compukter-vm/Cargo.toml computer::tests::foreground_parent_is_suspended_until_child_exit -- --exact
```

Expected: compile failure because `ComputerMachine` has no stack or `process_depth`.

- [ ] **Step 3: Refactor `ComputerMachine` to frames and intercept process requests**

Replace the single session and global pending terminal request with:

```rust
struct ProcessFrame {
    session: Session,
    capabilities: ProcessCapabilityMask,
    filesystem: FileCapability,
    pending_terminal_event: Option<RequestId>,
    pending_process: Option<RequestId>,
    reserved_heap_bytes: u64,
    reserved_frame_storage_bytes: u64,
}

pub struct ComputerMachine {
    processes: Vec<ProcessFrame>,
    terminal: TerminalDevice,
    active_terminal_owner: Option<usize>,
    filesystem: ComputerFileSystem,
    profile: ExecutionProfile,
    bindings: Box<[OwnedCapabilityBinding]>,
    process_limits: ProcessLimits,
    process_starts: u64,
    reserved_heap_bytes: u64,
    reserved_frame_storage_bytes: u64,
    maximum_text_code_units: usize,
}
```

Add `compukter/process@1` operation 0 as asynchronous `(String, I32) -> I32`. On a valid request, preserve its `RequestId` in the parent frame, read/verify/admit/start the child with only delegated bindings, reserve capacity, and push it. Advance only `processes.last_mut()`. Map a child terminal outcome to `ProcessResult`, pop it, release reservations, and call the parent's `resume_internal` with the stable `I32` code.

Because public `CapabilityBinding` borrows its operation slice, add a crate-private
`OwnedCapabilityBinding` containing owned namespace/name/schema data plus a
method that materializes borrowed bindings for each admission. `ComputerMachine`
copies the initial addon bindings once; child admission never retains caller
borrows.

- [ ] **Step 4: Run lifecycle and existing computer tests GREEN**

Run:

```bash
cargo test --manifest-path host/compukter-vm/Cargo.toml computer::tests -- --nocapture
```

Expected: new stack tests and all prior terminal/filesystem computer tests pass.

- [ ] **Step 5: Commit the foreground stack**

```bash
git -C host/compukter-vm add src/process.rs src/computer.rs src/execution/fixtures.rs
git -C host/compukter-vm commit -m "feat(process): run a bounded foreground stack (#518)"
```

### Task 3: Delegation, Quotas, Failure Mapping, and Teardown

**Files:**
- Modify: `host/compukter-vm/src/process.rs`
- Modify: `host/compukter-vm/src/computer.rs`
- Modify: `host/compukter-vm/src/execution/fixtures.rs`
- Test: `host/compukter-vm/src/computer.rs`

- [ ] **Step 1: Add failing matrix tests for every bounded result**

Cover invalid/negative/widening masks; depth and total-start limits; aggregate heap/frame reservation overflow; invalid/missing/non-executable artifacts; admission/start failure; child allocation/quota/trap/fault/host failure; addon binding filtering; unfinished terminal event cleanup; and full-stack drop.

```rust
assert_process_result(invalid_mask_fixture(), ProcessResult::InvalidCapabilities);
assert_process_result(missing_fixture(), ProcessResult::NotFound);
assert_process_result(non_executable_fixture(), ProcessResult::NotExecutable);
assert_process_result(trapping_fixture(), ProcessResult::Trapped);
assert_eq!(computer.process_depth(), 1);
assert!(computer.terminal_await_event().is_ok());
```

- [ ] **Step 2: Run the matrix and verify RED**

Run:

```bash
cargo test --manifest-path host/compukter-vm/Cargo.toml computer::tests::process_result_matrix -- --exact
```

Expected: assertions fail on the first unimplemented result/limit branch.

- [ ] **Step 3: Implement checked reservation and cleanup**

Validate the requested signed `I32` mask before parsing paths. Reserve depth, start count, declared heap capacity, and declared frame capacity with checked arithmetic before creating a visible child. Roll back all reservations on failure. Convert only child terminal outcomes to `ProcessResult`; root outcomes remain public `ComputerAdvanceOutcome`. Clear terminal event ownership when its frame exits, and let `Drop` release frames top-down without resuming parents.

- [ ] **Step 4: Run the complete VM suite GREEN**

Run:

```bash
cargo test --manifest-path host/compukter-vm/Cargo.toml --locked --offline
cargo fmt --manifest-path host/compukter-vm/Cargo.toml --check
cargo clippy --manifest-path host/compukter-vm/Cargo.toml --all-targets -- -D warnings
```

Expected: every VM unit/integration test passes, ignored fixture generators remain ignored, and clippy is clean.

- [ ] **Step 5: Commit quota/failure completeness**

```bash
git -C host/compukter-vm add src/process.rs src/computer.rs src/execution/fixtures.rs
git -C host/compukter-vm commit -m "feat(process): enforce delegated machine quotas (#518)"
```

### Task 4: Boot-from-ROM FFM Boundary

**Files:**
- Modify: `host/compukter-ffi/src/bridge.rs`
- Modify: `host/compukter-ffi/src/ffi_api.rs`
- Modify: `host/compukter-ffi/src/lib.rs`
- Modify: `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukters/lang/runtime/vm/LowLevelVmBridge.kt`
- Modify: `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukters/lang/runtime/vm/FfmBridge.kt`
- Modify: `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukters/lang/runtime/vm/VmSession.kt`
- Test: `host/compukter-ffi/src/bridge.rs`
- Test: `modules/native-runtime/src/test/kotlin/ru/lazyhat/compukters/lang/runtime/vm/VmSessionTest.kt`
- Test: `modules/native-runtime/src/test/kotlin/ru/lazyhat/compukters/lang/runtime/integration/FfmFileSystemIntegrationTest.kt`

- [ ] **Step 1: Write failing Rust/Kotlin boot-entry tests**

Assert that `create_boot_in_store(store, id, rom)` starts executable `/rom/boot`, rejects missing/non-executable/invalid boot entries through existing bounded create wire errors, and requires no artifact byte argument. Add a fake `LowLevelVmBridge` assertion that `VmSession.bootInStore` calls only the new method.

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
cargo test --manifest-path host/compukter-ffi/Cargo.toml boot_in_store
./gradlew-sandbox :native-runtime:test --tests '*VmSessionTest*boot*' --rerun-tasks
```

Expected: missing Rust export and Kotlin bridge methods.

- [ ] **Step 3: Add one bounded ABI entry**

Export:

```c
compukter_create_boot_in_store(
    store_handle,
    computer_id[16],
    rom_bytes,
    rom_length,
    output,
    output_capacity,
    written
)
```

The bridge admits the ROM, opens the persistent computer filesystem, constructs root owner authority, and calls `ComputerMachine::boot_in_filesystem(..., "/rom/boot", ProcessLimits::default())`. Add `LowLevelVmBridge.createBootInStore`, bind the symbol in `FfmBridge`, and expose `VmSession.bootInStore(store, id, romImage)` while retaining direct `open`/`openInStore` for tooling.

- [ ] **Step 4: Run FFI and native integration GREEN**

Run:

```bash
cargo test --manifest-path host/compukter-ffi/Cargo.toml --locked --offline
./gradlew-sandbox :native-runtime:test :native-runtime:nativeIntegrationTest --rerun-tasks
```

Expected: Rust FFI, fake bridge, and packaged native boot tests pass.

- [ ] **Step 5: Commit VM pointer and FFI boundary**

```bash
git add host/compukter-vm host/compukter-ffi modules/native-runtime
git commit -m "feat(runtime): boot computers from Rust ROM (#518)"
```

### Task 5: Trusted Process Intrinsic and Deterministic System Programs

**Files:**
- Create: `modules/compiler-k2/src/main/resources/compukter-api/process.kt`
- Create: `system/programs/boot.kt`
- Modify: `system/programs/shell.kt`
- Modify: `modules/compiler-k2/src/main/kotlin/ru/lazyhat/compukters/compiler/worker/k2/K2CompilerAdapter.kt`
- Modify: `modules/compiler-k2/src/main/kotlin/ru/lazyhat/compukters/compiler/worker/k2/TrustedIntrinsicRegistry.kt`
- Modify: `modules/compiler-k2/src/main/kotlin/ru/lazyhat/compukters/compiler/worker/k2/KotlinProjectLowering.kt`
- Modify: `modules/compiler-k2/build.gradle.kts`
- Test: `modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/TrustedIntrinsicRegistryTest.kt`
- Test: `modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/K2CompilerAdapterTest.kt`
- Test: `modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt`

- [ ] **Step 1: Write failing trusted-identity and deterministic compile tests**

Assert that only bundle `compukter.process-api@1`, callable `run`, suspend=true, `(String, Int) -> Int` resolves to the process capability identity and operation 0 async; same-named guest/package functions do not. Assert that lowering emits a deterministic capability table containing only capabilities actually referenced by trusted calls: boot has process at index 0, a terminal-only program keeps terminal at index 0, and shell assigns deterministic indices to process and terminal. Compile checked-in `boot.kt` and changed `shell.kt` twice and compare exact bytes.

- [ ] **Step 2: Run compiler tests and verify RED**

Run:

```bash
./gradlew-sandbox :compiler-k2:test --tests '*TrustedIntrinsicRegistryTest*' --tests '*MinimalScriptLoweringTest*process*' --rerun-tasks
```

Expected: process API resource/bundle/intrinsic and system sources are absent.

- [ ] **Step 3: Implement the trusted API and system behavior**

Add:

```kotlin
@file:Suppress("UNUSED_PARAMETER")
package process
suspend fun run(path: String, capabilities: Int): Int = 0
```

Register the resource under its own trusted bundle and make
`TrustedIntrinsic.CapabilityOperation` carry a stable capability identity rather
than a preassigned integer. Pre-scan trusted calls in `KotlinProjectLowering`,
sort the used identities, build only those manifest capability records, and map
each intrinsic to its resulting `CapabilityId`. This preserves terminal index 0
for existing terminal-only artifacts without forcing unused capabilities on a
child. `boot.kt` calls `process.run("/rom/shell", 7)`. Preserve shell built-ins;
for any other single token use the absolute token when its first character is
`/`, otherwise `/home/` plus the token, then call `process.run(path, 7)` and
print `process failed: <code>` only for non-zero results. Reject command
arguments for this task with the existing unknown-command message.

Create `generateBootArtifact` and keep `generateShellArtifact`; both depend on the compiler worker JAR, compile their checked-in source, and write deterministic outputs under `build/generated/system`.

- [ ] **Step 4: Run compiler suite GREEN**

Run:

```bash
./gradlew-sandbox :compiler-k2:test :compiler-k2:generateBootArtifact :compiler-k2:generateShellArtifact --rerun-tasks
```

Expected: trusted identity, deterministic compile, and guest shadowing tests pass; both artifacts exist.

- [ ] **Step 5: Commit compiler and system programs**

```bash
git add modules/compiler-k2 system/programs
git commit -m "feat(language): compile boot and process.run (#518)"
```

### Task 6: Canonical Extensionless ROM Packaging

**Files:**
- Modify: `modules/v26_1/v26_1-common/build.gradle.kts`
- Modify: `modules/v26_1/v26_1-common/src/main/kotlin/ru/lazyhat/compukters/minecraft/computer/SystemProgramImage.kt`
- Modify: `modules/v26_1/v26_1-common/src/main/kotlin/ru/lazyhat/compukters/minecraft/computer/SystemRomImage.kt`
- Modify: `modules/v26_1/v26_1-neoforge/build.gradle.kts`
- Test: `modules/v26_1/v26_1-common/src/test/kotlin/ru/lazyhat/compukters/minecraft/computer/SystemProgramImageTest.kt`
- Test: `modules/v26_1/v26_1-common/src/test/kotlin/ru/lazyhat/compukters/minecraft/computer/SystemRomImageTest.kt`
- Test: `modules/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/CompuktersModNativeBootstrapTest.kt`

- [ ] **Step 1: Write failing packaging and canonical-ROM tests**

Assert that program image loading returns defensive copies for both extensionless resources, ROM contains exactly executable `/rom/boot` then `/rom/shell`, repeated encoding is byte-identical, and the production JAR contains `system/programs/boot` and `system/programs/shell` but no `system/programs/*.cpkt`.

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
./gradlew-sandbox :v26_1-common:test --tests '*SystemProgramImageTest' --tests '*SystemRomImageTest' :v26_1-neoforge:test --tests '*CompuktersModNativeBootstrapTest' --rerun-tasks
```

Expected: boot APIs/resources are missing and shell still has `.cpkt`.

- [ ] **Step 3: Package two extensionless entries**

Make `processResources` depend on both generator tasks and rename artifacts to `boot` and `shell`. Replace `encodeShell` with a deterministic `encodePrograms(boot, shell)` that checks limits, emits entry count 2, sorts canonical paths, marks both files executable, and appends the existing SHA-256 digest. Update the production-JAR verifier to require both extensionless resources and reject `.cpkt` fixtures/resources.

- [ ] **Step 4: Run packaging tests GREEN**

Run:

```bash
./gradlew-sandbox :v26_1-common:test :v26_1-neoforge:test :v26_1-neoforge:build --rerun-tasks
```

Expected: all tests pass and JAR inspection reports only extensionless boot/shell system artifacts.

- [ ] **Step 5: Commit ROM packaging**

```bash
git add modules/v26_1/v26_1-common modules/v26_1/v26_1-neoforge/build.gradle.kts modules/v26_1/v26_1-neoforge/src/test
git commit -m "feat(rom): package extensionless boot and shell (#518)"
```

### Task 7: Remove the Production Installed-Artifact Bootstrap

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukters/core/device/computer/ProgramComputer.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukters/core/device/computer/ProgramComputerPorts.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukters/core/device/runtime/program/ProgramRuntimeHost.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukters/core/device/runtime/program/ProgramVmSession.kt`
- Modify: `modules/v26_1/v26_1-common/src/main/kotlin/ru/lazyhat/compukters/minecraft/computer/ComputerBlockEntity.kt`
- Modify: `modules/v26_1/v26_1-common/src/main/kotlin/ru/lazyhat/compukters/minecraft/computer/ComputerCarrier.kt`
- Delete: `modules/v26_1/v26_1-common/src/main/kotlin/ru/lazyhat/compukters/minecraft/computer/InstalledProgramStorage.kt`
- Delete: `modules/v26_1/v26_1-common/src/test/kotlin/ru/lazyhat/compukters/minecraft/computer/InstalledProgramStorageTest.kt`
- Test: existing core and common computer tests.

- [ ] **Step 1: Change tests first to require boot-mode production startup**

Replace fake installed-image expectations with a `ProgramVmSessionFactory.boot()` seam. Assert production filesystem construction calls boot without artifact bytes, missing/corrupt ROM maps to the existing bounded runtime failure, reboot calls boot again with a fresh session, and block NBT contains identity but no installed artifact payload.

- [ ] **Step 2: Run core/common tests and verify RED**

Run:

```bash
./gradlew-sandbox :core:test :v26_1-common:test --rerun-tasks
```

Expected: tests fail because `ProgramComputer` still loads an installed artifact and `ComputerBlockEntity` still owns `InstalledProgramStorage`.

- [ ] **Step 3: Introduce explicit artifact and ROM-boot launch modes**

Keep direct artifact startup only on generic `ProgramRuntimeHost.start(artifact)` for playground/conformance. Add `startBoot()` backed by `VmSession.bootInStore` to the persistent constructor. Make production `ProgramComputer.turnOn()` use its boot-capable host without an image source. Remove `ProgramImageSource`, installed artifact methods/storage/NBT fields, and the `SystemProgramImage::shell` boot callback from `ComputerBlockEntity`/carrier creation. Move the stable `"compukters"` NBT root key to the block entity/identity boundary, continue reading identity from the same child compound for save compatibility, and silently ignore the obsolete artifact field. Preserve stable identity and filesystem lease behavior.

- [ ] **Step 4: Run core/common tests GREEN**

Run:

```bash
./gradlew-sandbox :core:test :core:programRuntimeIntegrationTest :v26_1-common:test --rerun-tasks
```

Expected: ordinary test harness direct-artifact mode remains green; production persistent mode boots ROM and reboot creates a fresh stack.

- [ ] **Step 5: Commit bootstrap removal**

```bash
git add modules/core modules/v26_1/v26_1-common
git commit -m "refactor(computer): remove direct artifact bootstrap (#518)"
```

### Task 8: End-to-End Shell Child and Minecraft Lifecycle

**Files:**
- Modify: `host/compukter-vm/src/execution/fixtures.rs`
- Modify: `host/compukter-vm/src/computer.rs`
- Modify: `modules/v26_1/v26_1-neoforge/build.gradle.kts`
- Modify: `modules/v26_1/v26_1-neoforge/src/gameTest/kotlin/ru/lazyhat/compukters/impl/computer/ComputerBlockGameTest.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukters/core/device/runtime/program/integration/ProgramRuntimeHostIntegrationTest.kt`
- Test: the same files.

- [ ] **Step 1: Write failing nested-user and reboot integration tests**

Seed an executable `/home/hello` fixture whose verified artifact writes a unique terminal marker and halts. Drive boot to shell, enter `hello`, assert the marker appears, reboot, assert a fresh prompt appears exactly once from a new stack, and verify `/home/hello` remains executable/present.

- [ ] **Step 2: Run integration/GameTest and verify RED**

Run:

```bash
./gradlew-sandbox :core:programRuntimeIntegrationTest :v26_1-neoforge:runGameTestServer --rerun-tasks
```

Expected: shell cannot yet run the seeded child or reboot evidence still reflects direct startup.

- [ ] **Step 3: Add only the missing fixture and lifecycle glue**

Commit a reproducible executable writer/child artifact generated from Rust fixtures, include it only in GameTest resources, and extend the existing single Compukters lifecycle GameTest rather than registering a racing second store-lifecycle test. Use real terminal input, real save/reload, and real reboot APIs; do not add production admin filesystem access.

- [ ] **Step 4: Run integration/GameTest GREEN**

Run:

```bash
cargo test --manifest-path host/compukter-vm/Cargo.toml filesystem_game_test_artifacts_are_committed_and_reproducible
./gradlew-sandbox :core:programRuntimeIntegrationTest :v26_1-neoforge:runGameTestServer --rerun-tasks
```

Expected: the nested child marker, fresh post-reboot shell, stable computer ID, and persistent executable all pass.

- [ ] **Step 5: Commit end-to-end coverage**

```bash
git -C host/compukter-vm add src/computer.rs src/execution/fixtures.rs tests/fixtures
git -C host/compukter-vm commit -m "test(process): add nested foreground fixtures (#518)"
git add host/compukter-vm modules/core modules/v26_1/v26_1-neoforge
git commit -m "test(process): verify boot shell and user nesting (#518)"
```

### Task 9: Documentation and Completion Gate

**Files:**
- Modify: `README.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify only for factual correction: `docs/superpowers/specs/2026-08-24-issue-518-foreground-process-run-design.md`
- Modify only for tracking correction: `docs/superpowers/plans/2026-08-24-issue-518-foreground-process-run.md`

- [ ] **Step 1: Update current-state documentation**

Replace statements that direct shell boot or `process.run` are future work. Document `/rom/boot -> /rom/shell -> /home/<name>`, one active foreground lane, explicit integer capability masks, stable integer result codes, Rust ownership, and that compilation/cache behavior remains #522.

- [ ] **Step 2: Run stale-reference checks**

Run:

```bash
rg -n 'direct shell|shell\.cpkt|InstalledProgramStorage|process\.run.*next' README.md docs modules system
```

Expected: no production/current-state documentation or code references the removed bootstrap; historical issue plans may retain clearly historical text.

- [ ] **Step 3: Run the exact full verification gate**

Run:

```bash
./gradlew-sandbox-dev-parallel verifyLocalFull --rerun-tasks
cargo fmt --manifest-path host/compukter-vm/Cargo.toml --check
cargo clippy --manifest-path host/compukter-vm/Cargo.toml --all-targets -- -D warnings
git diff --check
git -C host/compukter-vm diff --check
```

Expected: exit 0, all Rust/JVM/GameTest tests pass, and both diffs are clean.

- [ ] **Step 4: Commit documentation and record evidence**

```bash
git add README.md docs
git commit -m "docs(process): document foreground boot flow (#518)"
```

Comment on #518 with exact Compukters and Compukter-VM commits, Rust/JVM/GameTest counts, packaged resource inspection, process depth exercised, result codes exercised, and reboot evidence.

- [ ] **Step 5: Complete the roadmap gate**

If every acceptance criterion is verified, close #518 as completed and set its Roadmap status to Done. Move #522 from Next to Now only after #518 is Done. Otherwise leave #518 in Now and state the exact remaining manual/in-game check.
