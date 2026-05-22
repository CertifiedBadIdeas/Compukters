# Collapse to a Single VM (LowVM) Implementation Plan

> Issue: [#44](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/44)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete the Image-VM + device-daemon track so LowVM (flat-RAM machine with MMIO devices) is the sole runtime; everything still builds and the Notebook still boots its bundled firmware.

**Architecture:** Subtractive change. Compiler already targets LowVM. Notebook already boots through `createRuxComputer` (LowVM JNI). The Image-VM is reachable only via `BackgroundDeviceVm.kt` → `NativeDeviceDaemonRuntime` → daemon JNI. Strategy: remove the dead consumer first, then strip the daemon JNI + Rust modules + Image-VM image format, then green-light all tests and update docs.

**Tech Stack:** Rust (`native/rux-vm`, `native/rux-compiler`), Kotlin (`modules/{core,native-runtime,v1_21_1/**}`), Gradle, JNI.

**Working tree:** `.worktrees/single-vm` (branch `feature/single-vm`).

**Final commit policy:** Stage everything and create **one** commit at the end (`chore(vm): collapse to single LowVM runtime; retire Image-VM and device daemon`). Do not commit between tasks unless the plan explicitly says so.

---

## Pre-flight

- [ ] **Step 1: Confirm location**

```bash
pwd
git status --short
git rev-parse --abbrev-ref HEAD
```

Expected: cwd ends with `.worktrees/single-vm`, branch `feature/single-vm`, clean tree.

- [ ] **Step 2: Establish green baseline**

```bash
cd native/rux-vm && cargo test --quiet 2>&1 | tail -5; cd ../..
cd native/rux-compiler && cargo test --quiet 2>&1 | tail -5; cd ../..
./gradlew :native-runtime:test :core:test :v1_21_1-common:test :v1_21_1-neoforge:test --console=plain 2>&1 | tail -5
```

Expected: all green. If anything fails, stop and ask before continuing — the plan only makes sense from a green start.

---

## Task 1: Audit `BackgroundDeviceVm` and decide its fate

**Files:**
- Read: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Read: any caller of `BackgroundDeviceVm` (use grep)

- [ ] **Step 1: Find consumers**

```bash
grep -RIn 'BackgroundDeviceVm' modules
```

- [ ] **Step 2: Read the file end-to-end**

Open `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`. Categorize each section:
- **Daemon-only:** anything touching `nativeDaemonBindings`, `nativeDeviceDaemonHandle`, `nativeDaemonRuntime`, `bootNativeDaemon`, host-request loop, daemon display-frame drain, `NativeDeviceDaemonRuntime`, `CkVmImageAbi`, `compileImage`.
- **LowVM-relevant:** anything touching `RuxComputer`, `createRuxComputer`, `runRuxComputerUntilSignal`, `pushRuxComputerSerialInput`, `ruxComputerDisplay0Snapshot`.
- **Glue:** lifecycle (`boot()`, `tick()`, `close()`), thread pool, locks.

- [ ] **Step 3: Decide**

Two outcomes:
- (A) The class has **no** LowVM responsibility after the daemon pieces are removed → mark the entire file for deletion in Task 7. Note the call sites that need to be updated (which will be removed wholesale).
- (B) Some LowVM glue remains → mark the file for "trim" in Task 7. Write a one-paragraph note in your scratch space describing what should remain.

Record the decision in the worktree as `.notes/background-device-vm.md` (this file is gitignored only inside `.worktrees/`; do not commit it — it's a scratch pad for Task 7). If `.notes/` doesn't exist, create it.

```bash
mkdir -p .notes
$EDITOR .notes/background-device-vm.md   # or write via file tool
```

The note must contain at least: decision letter (A/B), and for (B) the explicit list of methods/fields to keep.

- [ ] **Step 4: Do not commit anything.** No code changes in this task.

---

## Task 2: Delete device-daemon JNI surface (Rust)

**Files:**
- Modify: `native/rux-vm/src/jni.rs`

- [ ] **Step 1: Locate the device-daemon region**

```bash
grep -n 'DeviceDaemon\|device_daemon\|DEVICE_DAEMON_HANDLES\|NEXT_DEVICE_DAEMON_HANDLE' native/rux-vm/src/jni.rs | head -40
```

You will find the daemon block spans roughly lines 19–695 (the file may have shifted; trust grep).

- [ ] **Step 2: Delete every JNI entry point with "DeviceDaemon" in its name**

Remove every `pub extern "system" fn Java_*_*DeviceDaemon*Native(...)` function. There are 15 of them:

```
createDeviceDaemonNative
freeDeviceDaemonNative
refillDeviceDaemonQuotaNative
runDeviceDaemonReadyNative
bootDeviceDaemonNative
drainDeviceDaemonHostRequestsNative
completeDeviceDaemonHostRequestNative
completeDeviceDaemonCompileProgramNative
enqueueDeviceDaemonEventNative
attachDeviceDaemonFilesystemNative
attachDeviceDaemonDisplayNative
detachDeviceDaemonDisplayNative
drainDeviceDaemonDisplayFramesNative
deviceDaemonDisplayWakeSequenceNative
waitForDeviceDaemonDisplayWakeNative
```

- [ ] **Step 3: Delete daemon helpers**

Remove:
- `static DEVICE_DAEMON_HANDLES: ...`
- `static NEXT_DEVICE_DAEMON_HANDLE: ...`
- helper fns `with_device_daemon_mut`, `register_device_daemon_handle`, `unregister_device_daemon_handle`, `shared_device_daemon_kernel_handle`, `encode_device_daemon_host_requests`, `event_arguments_from_payload`, `encode_display_frames`
- the `use crate::device_daemon::...;` line
- the `use crate::runtime_kernel::...;` line if present
- the `use crate::filesystem::...;` line if present
- `use crate::image::...;` and `use crate::image_runner::...;` lines if not used by the surviving LowVM JNI

- [ ] **Step 4: Compile check**

```bash
cd native/rux-vm && cargo check 2>&1 | tail -30; cd ../..
```

Expect errors only from `image_runner` / `device_daemon` mod declarations in `lib.rs` (still present) or from `jni.rs` references to types you missed. Fix until `cargo check` succeeds for `jni.rs` itself. Do NOT delete `image.rs`, `image_runner.rs`, etc. yet — that is Task 3.

- [ ] **Step 5: No commit.** Move on.

---

## Task 3: Delete Image-VM Rust modules and tests

**Files:**
- Delete: `native/rux-vm/src/image.rs`
- Delete: `native/rux-vm/src/image_runner.rs`
- Delete: `native/rux-vm/src/device_daemon.rs`
- Delete: `native/rux-vm/src/runtime_kernel.rs`
- Delete: `native/rux-vm/src/filesystem.rs`
- Delete: `native/rux-vm/tests/image_decode.rs`
- Delete: `native/rux-vm/tests/image_runner.rs`
- Modify: `native/rux-vm/src/lib.rs`

- [ ] **Step 1: Delete the files**

```bash
rm native/rux-vm/src/image.rs \
   native/rux-vm/src/image_runner.rs \
   native/rux-vm/src/device_daemon.rs \
   native/rux-vm/src/runtime_kernel.rs \
   native/rux-vm/src/filesystem.rs \
   native/rux-vm/tests/image_decode.rs \
   native/rux-vm/tests/image_runner.rs
```

- [ ] **Step 2: Remove module declarations**

In `native/rux-vm/src/lib.rs`, delete exactly these lines (their order in the file may vary):

```rust
pub mod device_daemon;
pub mod filesystem;
pub mod image;
pub mod image_runner;
pub mod runtime_kernel;
```

- [ ] **Step 3: Sweep stragglers**

```bash
grep -RIn -E 'crate::(image|image_runner|device_daemon|runtime_kernel|filesystem)\b' native/rux-vm/src
```

Expected: no matches. If there are matches (e.g., a stale `use` line in `signal.rs`, `value.rs`, or a `mod.rs`), open that file and delete the matching `use` or `mod` reference. Loop until grep is silent.

- [ ] **Step 4: cargo check the crate**

```bash
cd native/rux-vm && cargo check 2>&1 | tail -40; cd ../..
```

Expect no errors. If there are errors, they are real signs that something in `signal.rs` or `value.rs` still references Image-VM types (most likely `VmSignal::HostCall`). Move to Task 4 to fix.

---

## Task 4: Trim `signal.rs` `HostCall` variant

**Files:**
- Modify: `native/rux-vm/src/signal.rs`

- [ ] **Step 1: Identify the variant**

```bash
grep -n 'HostCall\|SIGNAL_HOST_CALL' native/rux-vm/src/signal.rs
```

- [ ] **Step 2: Remove the variant and its codec arms**

Delete:
- `VmSignal::HostCall { ... }` arm from the enum
- the `const SIGNAL_HOST_CALL: u8 = 4;` constant
- every encode/decode arm that references `HostCall` / `SIGNAL_HOST_CALL`

If the constant `4` is now unused, that's fine — do **not** renumber surviving variants (binary format must stay stable for LowVM consumers).

- [ ] **Step 3: Sweep for remaining HostCall references**

```bash
grep -RIn 'HostCall\|SIGNAL_HOST_CALL\|host_call' native/rux-vm/src
```

Expected: no matches (the `host_call_*` metric fields lived in `image_runner.rs` and are already gone). If the JNI's signal-encoding loop still references the variant, remove that arm too.

- [ ] **Step 4: cargo check + cargo test (rux-vm crate)**

```bash
cd native/rux-vm && cargo check 2>&1 | tail -10 && cargo test --quiet 2>&1 | tail -10; cd ../..
```

Expected: green. If `signal_codec.rs` test fails because it asserts a `HostCall` round-trip, delete that specific assertion (it is testing the variant we just removed).

---

## Task 5: cargo green across Rust workspace

- [ ] **Step 1: Test rux-vm**

```bash
cd native/rux-vm && cargo test --quiet 2>&1 | tail -15; cd ../..
```

Expected: all tests pass. The remaining tests should be: `display_engine`, `low_image_abi_fixtures`, `low_image_abi_opcode_metadata`, `low_image_decode`, `low_image_disasm`, `low_image_runner`, `rux_computer`, `signal_codec`.

- [ ] **Step 2: Test rux-compiler**

```bash
cd native/rux-compiler && cargo test --quiet 2>&1 | tail -15; cd ../..
```

Expected: green. The compiler never used Image-VM at runtime, so this should pass without changes. If the compiler's `Cargo.toml` `[dev-dependencies]` pull `rux_vm::image_runner`, replace with `rux_vm::low_image_runner` (it almost certainly already does).

- [ ] **Step 3: cargo fmt**

```bash
cd native/rux-vm && cargo fmt; cd ../..
cd native/rux-compiler && cargo fmt; cd ../..
```

- [ ] **Step 4: No commit yet.**

---

## Task 6: Delete Kotlin native daemon bindings

**Files:**
- Delete: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/NativeDeviceDaemonRuntime.kt`
- Modify: `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`

- [ ] **Step 1: Delete NativeDeviceDaemonRuntime.kt**

```bash
rm modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/NativeDeviceDaemonRuntime.kt
```

- [ ] **Step 2: Strip daemon surface from NativeVmBindings.kt**

Open `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`.

Delete every public function whose name contains `DeviceDaemon`:

```
createDeviceDaemon
freeDeviceDaemon
refillDeviceDaemonQuota
runDeviceDaemonReady
bootDeviceDaemon
drainDeviceDaemonHostRequests
completeDeviceDaemonHostRequest
completeDeviceDaemonCompileProgram
enqueueDeviceDaemonEvent
attachDeviceDaemonFilesystem
attachDeviceDaemonDisplay
detachDeviceDaemonDisplay
drainDeviceDaemonDisplayFrames
deviceDaemonDisplayWakeSequence
waitForDeviceDaemonDisplayWake
```

Delete every `private external fun *DeviceDaemon*Native(...)` declaration paired with them.

Delete the data classes:
- `NativeDeviceDaemonBootSummary`
- `NativeDeviceDaemonTickSummary`
- `NativeDeviceDaemonHostRequest`

(If `NativeDeviceDaemonHostRequest` is declared in a separate file, delete that file too. Confirm with grep: `grep -RIn 'class NativeDeviceDaemonHostRequest' modules`.)

- [ ] **Step 3: compileKotlin spot check (native-runtime only)**

```bash
./gradlew :native-runtime:compileKotlin --console=plain 2>&1 | tail -20
```

Expected: green (this module doesn't depend on Image-VM consumers). If it fails, the failure points at a leftover reference. Fix it before moving on.

---

## Task 7: Excise `BackgroundDeviceVm` and other Image-VM consumers

**Files:**
- Modify or delete: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Check: anything in `modules/**` still referencing `NativeDaemonBindings`, `NativeVmDaemonBindings`, `CkVmImageAbi`, `compileImage`

- [ ] **Step 1: Recheck consumers**

```bash
grep -RIn 'NativeDeviceDaemonRuntime\|NativeDaemonBindings\|NativeVmDaemonBindings\|nativeDeviceDaemonHandle\|nativeDaemonRuntime' modules
```

- [ ] **Step 2: Apply the Task 1 decision**

**If decision was (A) "delete BackgroundDeviceVm entirely":**

```bash
rm modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt
```

Then find every consumer of `BackgroundDeviceVm`:

```bash
grep -RIn 'BackgroundDeviceVm' modules
```

For each consumer: if it's the only thing using daemon machinery, delete the consumer too. If it's a higher-level class with other responsibilities, replace the `BackgroundDeviceVm` usage with the LowVM path that `NotebookBlockEntity` already uses (`RuxComputerRuntimeFactory.createFromResource(...)`). Update the call sites accordingly.

**If decision was (B) "trim":**

In `BackgroundDeviceVm.kt`, delete:
- constructor parameter `nativeDaemonBindings: NativeDaemonBindings`
- field/init of `nativeDeviceDaemonHandle`
- field/init of `nativeDaemonRuntime`
- `bootNativeDaemon()` method
- daemon executor loop
- host-request drain loop
- daemon display-frame drain
- locks scoped to daemon (`nativeDeviceKernelLock.read/write` accesses)
- imports of `NativeDeviceDaemonRuntime`, `NativeDeviceDaemonHostRequest`, `CkVmImageAbi`, `compileImage`

Rewire whatever methods used the daemon to call the existing LowVM path through `NativeVmBindings.createRuxComputer` etc. The exact rewiring is in the scratch note from Task 1.

- [ ] **Step 3: Check for stale image-VM imports**

```bash
grep -RIn 'lang\.runtime\.image\.CkVmImageAbi\|lang\.runtime\.image\.compileImage' modules
```

Delete any imports / references that remain. If `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageAbi.kt` (or similar) is purely Image-VM, delete the file.

- [ ] **Step 4: compileKotlin**

```bash
./gradlew :core:compileKotlin :v1_21_1-common:compileKotlin :v1_21_1-neoforge:compileKotlin --console=plain 2>&1 | tail -25
```

Expected: green. If a stale test file fails compilation, jump to Task 8 step 1 to handle it.

---

## Task 8: Green-light Gradle tests

**Files:**
- Read: `modules/**/src/test/kotlin/**`

- [ ] **Step 1: compileTestKotlin sweep**

```bash
./gradlew :native-runtime:compileTestKotlin :core:compileTestKotlin :v1_21_1-common:compileTestKotlin :v1_21_1-neoforge:compileTestKotlin --console=plain 2>&1 | tail -40
```

If errors point at tests that exercise daemon behavior (e.g. tests that build a `NativeDeviceDaemonRuntime`, attach a filesystem, drive host requests), delete those test files — they cover functionality we just retired.

Re-run the compile until green.

- [ ] **Step 2: Run tests**

```bash
./gradlew :native-runtime:test :core:test :v1_21_1-common:test :v1_21_1-neoforge:test --console=plain 2>&1 | tee /tmp/test.log | tail -30
```

Expected: all green. The surviving `RuxFirmwareResourceTest` should pass because the bundled `.ruxi` resources are LowVM `low_image` artifacts.

- [ ] **Step 3: Sanity-check the firmware boot path manually (read, no run)**

Open `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/notebook/block/NotebookBlockEntity.kt`. Confirm that the boot path ends at `NativeVmBindings.createRuxComputer(...)` and not at `createDeviceDaemon` / `bootDeviceDaemon`. Confirm `runRuxComputerUntilSignal`, `pushRuxComputerSerialInput`, and `ruxComputerDisplay0Snapshot` are still wired.

---

## Task 9: Docs

**Files:**
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/abi/CHANGELOG.md`
- Read (and update if needed): `docs/abi/FREEZE-CHECKLIST.md`, `docs/abi/PRE-FREEZE-GAPS.md`

- [ ] **Step 1: ARCHITECTURE.md**

Find any section mentioning "Image-VM", "device daemon", "host imports", or "two VMs". Replace with a description that there is **one VM** (LowVM, flat-RAM, MMIO bus, single-process). Keep the wording crisp; no need to rewrite the whole document.

```bash
grep -n -E 'Image[- ]VM|device daemon|host import|two VMs|host_import' docs/ARCHITECTURE.md
```

- [ ] **Step 2: CHANGELOG.md**

Append a dated entry under the existing format:

```markdown
## 2026-05-23 — Single VM (Issue #44)

- Image-VM (`image.rs`, `image_runner.rs`) retired.
- Device-daemon runtime kernel (`device_daemon.rs`, `runtime_kernel.rs`) retired.
- Host-imported filesystem (`filesystem.rs`) retired together with the daemon.
- `VmSignal::HostCall` removed from the signal codec.
- All `*DeviceDaemon*` JNI entry points removed.
- LowVM (`low_image*`, `computer/**`) is the sole runtime.
```

(Match the file's existing style; if the file uses a different header format, follow it.)

- [ ] **Step 3: FREEZE-CHECKLIST / PRE-FREEZE-GAPS**

```bash
grep -n -E 'Image[- ]VM|device daemon|host import|host_import|CallHost' docs/abi/FREEZE-CHECKLIST.md docs/abi/PRE-FREEZE-GAPS.md
```

For each hit, decide: still relevant (rephrase to point at LowVM) or obsolete (delete the bullet). If unclear, leave a `> TODO(#44):` note inline so it surfaces during the next freeze review.

- [ ] **Step 4: Scratch note cleanup**

```bash
rm -rf .notes
```

The directory was a private workpad; nothing in it should be committed.

---

## Final verification

- [ ] **Step 1: Whole-tree sweep**

```bash
grep -RIn -E 'image_runner|device_daemon|runtime_kernel|::filesystem|CallHost|DeviceDaemon|host_import|HostImport' native/rux-vm/src native/rux-vm/tests modules
```

Expected: zero matches. If `HostImport` survives anywhere outside `docs/`, hunt it down.

- [ ] **Step 2: Full Gradle build**

```bash
./gradlew build --console=plain 2>&1 | tail -25
```

Expected: green. (If your machine struggles, `:native-runtime:test :core:test :v1_21_1-common:test :v1_21_1-neoforge:test` is the acceptance subset.)

- [ ] **Step 3: Full Rust test**

```bash
(cd native/rux-vm && cargo test --quiet 2>&1 | tail -10)
(cd native/rux-compiler && cargo test --quiet 2>&1 | tail -10)
```

Expected: green.

- [ ] **Step 4: Single commit**

```bash
git add -A
git status --short
git commit -m "chore(vm): collapse to single LowVM runtime; retire Image-VM and device daemon

Issue: #44"
```

- [ ] **Step 5: Inspect commit**

```bash
git show --stat HEAD | head -50
git diff --stat HEAD~1 HEAD | tail -15
```

Sanity check: only deletions in `image.rs`, `image_runner.rs`, `device_daemon.rs`, `runtime_kernel.rs`, `filesystem.rs`, the related tests, daemon JNI/Kotlin code, plus a small trim in `signal.rs`, `lib.rs`, `NativeVmBindings.kt`, `BackgroundDeviceVm.kt`, docs.

- [ ] **Step 6: Do not push.** Stop here. The user will review the worktree, decide on merge strategy, and close #44 via the `finishing-a-development-branch` skill.
