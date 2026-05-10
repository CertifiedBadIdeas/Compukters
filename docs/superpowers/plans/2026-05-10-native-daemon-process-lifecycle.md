# Native Daemon Process Lifecycle Implementation Plan

> **For agentic workers:** execute task-by-task and commit each task independently on `dev`.

**Goal:** move `process.*` hot-path operations into the native daemon while keeping CKL source loading and compilation in
Kotlin.

**Architecture:** Rust daemon owns process state, image handles, arguments, working directories, and parent/child wait
semantics. Kotlin serves typed daemon requests for source compilation and unresolved host services.

---

## Task 1: Native Process Metadata Fast Path

**Files:**
- Modify: `native/ckl-vm/src/image_runner.rs`
- Modify: `native/ckl-vm/src/device_daemon.rs`
- Test: `native/ckl-vm/src/device_daemon.rs`
- Test: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`

- [ ] Add process argument storage to `ImageVmHandle`.
- [ ] Set boot image argument in `DeviceDaemon::boot_image`.
- [ ] Fast-path `process.argument` when an image has a native process argument.
- [ ] Fast-path `process.currentDirectory` from image-local working directory.
- [ ] Keep fallback behavior for non-daemon native images without a process argument.
- [ ] Add tests showing daemon `process.argument` reaches `system.log` without an intermediate process host request.
- [ ] Commit as `feat: add daemon process metadata fast path`.

## Task 2: Daemon Filesystem Attachment

**Files:**
- Modify: `native/ckl-vm/src/device_daemon.rs`
- Modify: `native/ckl-vm/src/jni.rs`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`

- [ ] Add daemon-native filesystem attach JNI binding.
- [ ] Attach `nativeFilesystemRoot` to daemon kernels during `BackgroundDeviceVm` construction.
- [ ] Fast-path `process.changeDirectory` by validating directories through the attached native filesystem.
- [ ] Add tests for daemon `currentDirectory/changeDirectory`.
- [ ] Commit as `feat: attach filesystem to native daemon`.

## Task 3: Typed CompileProgram Request

**Files:**
- Modify: `native/ckl-vm/src/device_daemon.rs`
- Modify: `native/ckl-vm/src/jni.rs`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`

- [ ] Convert daemon `process.spawn/run` signals into `CompileProgram` requests.
- [ ] Include parent pid, reserved child pid, mode, argument, path, and working directory in the request.
- [ ] Add JNI decode coverage for `compileProgram` request payloads.
- [ ] Keep parent parked while Kotlin compiles.
- [ ] Commit as `feat: request daemon child program compilation`.

## Task 4: Compile Completion and Child Scheduling

**Files:**
- Modify: `native/ckl-vm/src/device_daemon.rs`
- Modify: `native/ckl-vm/src/jni.rs`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/NativeDeviceDaemonRuntime.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`

- [ ] Add `completeDeviceDaemonCompileProgram(...)`.
- [ ] Register child process and attach compiled image on success.
- [ ] Resume `spawn` parent with child pid.
- [ ] Park `run` parent as child waiter and resume it with child exit code.
- [ ] Return exit code `1` for load/compile failures.
- [ ] Add integration tests for daemon spawn, wait, and run.
- [ ] Commit as `feat: schedule daemon child processes`.

## Task 5: Terminal Daemon Profiling

**Files:**
- Modify profiling workload/report files as needed.
- Update this plan.

- [ ] Run daemon terminal profiling.
- [ ] Verify boot reaches terminal and shell prompts through daemon process scheduling.
- [ ] Compare host-call counts for `process.*` before/after.
- [ ] Commit docs/report updates.
