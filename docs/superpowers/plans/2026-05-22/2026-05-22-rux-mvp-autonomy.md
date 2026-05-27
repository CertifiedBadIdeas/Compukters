# Rux MVP Autonomy Implementation Plan

> Issue: [#26](https://github.com/lazyhat/Compukter-Kraft/issues/26)

> **Status (2026-05-22):** Tasks 1 + 2 landed in commit `49a62e42`. Tasks 3–10 completed in a follow-up commit on `dev`: core CKIM runtime (`BackgroundDeviceVm`, `DeviceVmSupervisor`, `RuntimeDeviceImpl`, `DeviceManager` helpers) removed; `LanguageServices` + `lang/frontend/**` + `lang/runtime/image/**` deleted; CKIM JNI exports stripped from `NativeVmBindings` and `native/rux-vm/src/jni.rs`; `image.rs` / `image_runner.rs` kept (still used by the device daemon); `bios.ck` and the `/rom/*.ck` ROM directory plus dead `ckl.benchmark.*` Gradle tasks removed; `:compiler` Gradle module renamed to `:native-runtime`; docs (LANGUAGE, ARCHITECTURE, ABI CHANGELOG, README) refreshed. All `:native-runtime`, `:core`, `:v1_21_1-common`, `:v1_21_1-neoforge` tests are green; `cargo test` in `native/rux-vm` is green.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the CKL/CKIM stack from Compukter-Kraft, leaving Notebook as the only player-facing computer block, booted end-to-end through the Rust toolchain (`native/rux-compiler` → `rux-laptop.ruxi` → `native/rux-vm`).

**Architecture:** Surgical deletion. Workbench / Computer-block / Terminal items are unregistered first; that makes the CKL stack unreachable in production. Each task removes one well-bounded slice (Kotlin user-facing layer, Kotlin runtime layer, Rust CKIM VM, assets, docs) and verifies that build + Notebook boot still work. The `modules/compiler` Gradle module gets renamed to `modules/native-runtime` at the end of the cleanup because it stops containing any compiler logic.

**Tech Stack:** Kotlin (Gradle, JUnit), Rust (Cargo), Minecraft NeoForge 1.21.1, JNI.

**Spec:** [docs/superpowers/specs/2026-05-22/2026-05-22-rux-mvp-autonomy-design.md](../../specs/2026-05-22/2026-05-22-rux-mvp-autonomy-design.md).

---

## File Map

Changed at task granularity. Created files are explicit; everything else is delete or modify.

| File | Change | Task |
|---|---|---|
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/binding/ModObjects.kt` | Modify — unregister Workbench/Computer/Terminal | 1 |
| `modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/CompukterKraftMod.kt` | Modify — drop unused registry references | 1 |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/**` | Delete | 2 |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/terminal/item/TerminalItem.kt` | Delete | 2 |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/serial/**` | Delete | 2 |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerBlock.kt` | Delete (the standalone Computer block, not the abstract base) | 2 |
| `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerBlockEntity.kt` | Delete (concrete entity; abstract base stays) | 2 |
| `modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/computer/block/NeoForgeComputerBlockEntity.kt` | Delete | 2 |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt` | Delete | 3 |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/DeviceVmSupervisor.kt` | Delete | 3 |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/WorkspaceDeviceIdeHost.kt` | Delete | 3 |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImpl.kt` | Delete | 3 |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/DeviceProgramSupport.kt` | Delete | 3 |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/DeviceManager.kt` | Modify — drop workspace/IDE helpers, keep `RuntimeDevice` registry | 3 |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/language/LanguageServices.kt` | Delete | 4 |
| `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/language/**` (rest of pkg) | Delete | 4 |
| `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/**` | Delete | 4 |
| `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/**` | Delete | 4 |
| `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt` | Modify — remove CKIM JNI methods | 5 |
| `modules/compiler/src/test/**` | Modify — delete CKL/CKIM tests, keep LowImage+RuxComputer tests | 5 |
| `native/rux-vm/src/jni.rs` | Modify — drop `createImage`/`runImage`/`resumeImage`/`imageMetrics`/`freeImage` exports | 6 |
| `native/rux-vm/src/image.rs` | Delete | 6 |
| `native/rux-vm/src/image_runner.rs` | Delete | 6 |
| `native/rux-vm/src/value.rs` | Audit & delete if CKIM-only | 6 |
| `native/rux-vm/src/signal.rs` | Audit & delete if CKIM-only | 6 |
| `native/rux-vm/src/lib.rs` | Modify — drop deleted `pub mod` lines | 6 |
| `native/rux-vm/tests/**` | Modify — delete CKIM-targeted tests | 6 |
| `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/bios.ck` | Delete | 7 |
| `modules/compiler/build.gradle.kts` | Modify — rename `ckl.benchmark.*` properties (or delete CKL benches), drop CKL source sets | 7 |
| `settings.gradle.kts` | Modify — rename `:compiler` → `:native-runtime` | 8 |
| `modules/compiler/` directory | Rename → `modules/native-runtime/` | 8 |
| `modules/core/build.gradle.kts` | Modify — `projects.compiler` → `projects.nativeRuntime` | 8 |
| `modules/v1_21_1/v1_21_1-*/build.gradle.kts` | Modify — same rename | 8 |
| `docs/LANGUAGE.md` | Replace with retirement stub | 9 |
| `docs/ARCHITECTURE.md` | Modify — drop CKL/CKIM, promote Rux/RuxComputer | 9 |
| `docs/abi/CHANGELOG.md` | Modify — record CKIM retirement | 9 |
| `README.md` | Modify — drop CKL flow | 9 |

---

## Task 1: Move issue to "Now" and unregister CKL-bound player surfaces

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/binding/ModObjects.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/CompukterKraftMod.kt`

- [ ] **Step 1: Set roadmap status to Now**

Run:

```bash
ITEM=$(gh project item-list 6 --owner lazyhat --limit 100 --format json --jq '.items[] | select(.content.number == 26) | .id')
gh project item-edit \
  --project-id PVT_kwHOBkSydc4BYgqn \
  --field-id PVTSSF_lAHOBkSydc4BYgqnzhTmClk \
  --id "$ITEM" \
  --single-select-option-id 0ea1b704
```

Expected: command succeeds. Verify with `gh project item-list 6 --owner lazyhat --limit 100 --format json --jq '.items[] | select(.content.number == 26) | .status'` → `Now`.

- [ ] **Step 2: Inventory the registration sites**

Open [ModObjects.kt](modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/binding/ModObjects.kt) and identify the lateinit properties / register-blocks for:
- `workbenchBlock`, `workbenchBlockEntityType`, `workbenchMenuType`, `workbenchItem` (and any related fields).
- `computerBlock`, `computerBlockEntityType`, `computerItem` (the standalone Computer block; NOT the abstract base used by Notebook).
- `terminalItem`, `serialTerminalItem`, `serialTerminalMenuType`, `terminalMenuType` (if separate).

Write down (in your scratch buffer, not the file) every symbol that must be removed.

- [ ] **Step 3: Remove the registrations**

Edit `ModObjects.kt` and delete the identified properties plus their registration calls. Leave alone: `notebookBlock`, `notebookBlockEntityType`, `notebookItem`, `computerMenuType`, `computerControlMenuType` if Notebook reuses them, and anything Notebook-side.

Then edit `CompukterKraftMod.kt` to drop references to the just-deleted symbols.

- [ ] **Step 4: Build and let the compiler surface the dependent code**

Run:

```bash
./gradlew :v1_21_1:v1_21_1-neoforge:compileKotlin --console=plain
```

Expected: many "unresolved reference" errors pointing to Workbench/Computer-block/Terminal callers. That is the deletion target list for Task 2.

- [ ] **Step 5: Commit**

```bash
git add modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/binding/ModObjects.kt \
        modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/CompukterKraftMod.kt
git commit -m "chore(ck-removal): unregister Workbench/Computer/Terminal blocks and items

Refs #26"
```

Build will be red after this commit. That is intentional and will be fixed by Task 2.

---

## Task 2: Delete unreachable player-facing Kotlin

**Files:**
- Delete: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/**`
- Delete: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/terminal/item/TerminalItem.kt`
- Delete: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/serial/**`
- Delete: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerBlock.kt`
- Delete: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerBlockEntity.kt`
- Delete: `modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/computer/block/NeoForgeComputerBlockEntity.kt`
- Delete: matching test files under `src/test/` mirrors

- [ ] **Step 1: Verify Notebook does NOT extend `ComputerBlockEntity` (concrete)**

```bash
rg -n 'class NotebookBlockEntity' modules/v1_21_1
rg -n 'extends ComputerBlockEntity|: ComputerBlockEntity' modules/v1_21_1
```

Expected: `NotebookBlockEntity` extends `ComputerBlockEntity`. **That means `ComputerBlockEntity.kt` cannot just be deleted — it is the concrete base for Notebook.** Re-read it: if it has no logic beyond `AbstractComputerBlockEntity`, fold Notebook to extend `AbstractComputerBlockEntity` directly (subtask 1a). Otherwise keep the file but ensure `NeoForgeComputerBlockEntity` is no longer needed (Notebook has its own `NeoForgeNotebookBlockEntity`).

Document the decision in the commit message of Step 6.

- [ ] **Step 1a: If `ComputerBlockEntity` is non-trivial, keep it but unbind its registration**

Already unbound in Task 1. Skip deletion in that case. Make `NotebookBlockEntity` continue to extend it, but ensure the standalone `ComputerBlock.kt` (which references `ComputerBlockEntity`) is the one removed.

- [ ] **Step 1b: If `ComputerBlockEntity` is a trivial pass-through**

Change `NotebookBlockEntity` to extend `AbstractComputerBlockEntity` directly. Then delete `ComputerBlockEntity.kt` and `NeoForgeComputerBlockEntity.kt`.

- [ ] **Step 2: Delete `workbench/**`**

```bash
git rm -r modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench
git rm -r modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/workbench 2>/dev/null || true
git rm -r modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/workbench 2>/dev/null || true
```

- [ ] **Step 3: Delete `terminal/item/TerminalItem.kt` and `serial/**`**

```bash
git rm modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/terminal/item/TerminalItem.kt
git rm -r modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/serial
git rm -r modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/serial 2>/dev/null || true
```

If `terminal/` contains anything besides `item/TerminalItem.kt` (e.g., shared screens used by Notebook) — leave those. Verify with `ls modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/terminal`.

- [ ] **Step 4: Delete the standalone Computer block**

Per the decision in Step 1:

```bash
git rm modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerBlock.kt
# Step 1b only:
git rm modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerBlockEntity.kt
git rm modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/computer/block/NeoForgeComputerBlockEntity.kt
```

- [ ] **Step 5: Rebuild and resolve dangling references**

```bash
./gradlew :v1_21_1:v1_21_1-neoforge:compileKotlin --console=plain
```

Expected: compile succeeds, OR new "unresolved reference" errors pointing to Task 3 candidates (`BackgroundDeviceVm`, `RuntimeDeviceImpl`, etc.). If unrelated files (e.g., a util that imported `WorkbenchBlockEntity`) break, delete the imports or the dead code in this commit.

- [ ] **Step 6: Verify Notebook still compiles and is wired**

```bash
rg -n 'NotebookBlockEntity|notebookBlock' modules/v1_21_1 | head -40
./gradlew :v1_21_1:v1_21_1-neoforge:compileKotlin --console=plain | head -40
```

Confirm Notebook references remain intact.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "chore(ck-removal): delete Workbench/Computer/Terminal Kotlin sources

Decision: ComputerBlockEntity {kept|folded into AbstractComputerBlockEntity}.

Refs #26"
```

---

## Task 3: Delete CKL runtime infrastructure in `modules/core`

**Files:**
- Delete: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Delete: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/DeviceVmSupervisor.kt`
- Delete: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/WorkspaceDeviceIdeHost.kt`
- Delete: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImpl.kt`
- Delete: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/DeviceProgramSupport.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/DeviceManager.kt`
- Delete: matching `modules/core/src/test/**` mirrors

- [ ] **Step 1: Confirm the targets have no remaining production callers**

```bash
rg -n 'BackgroundDeviceVm|DeviceVmSupervisor|WorkspaceDeviceIdeHost|RuntimeDeviceImpl|DeviceProgramSupport|ComputerProgramCompiler' \
  modules/core/src/main modules/v1_21_1/*/src/main modules/compiler/src/main
```

Expected: hits only inside files being deleted. If `RuxRuntimeDevice` or `DeviceManager` use any of these, treat that as a real coupling and unwind it before deletion.

- [ ] **Step 2: Trim `DeviceManager`**

Open [DeviceManager.kt](modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/DeviceManager.kt). Remove the `vmSupervisor` field, the `getOrCreate(...)` helper, and the workspace bookkeeping that was exclusively used by `WorkbenchBlockEntity` / `RuntimeDeviceImpl`. Keep the `RuntimeDevice` registry (`add`, `get`, `remove`) plus `allocateDeviceId` because `AbstractComputerBlockEntity` and `RuxRuntimeDevice` use it.

Build incrementally:

```bash
./gradlew :core:compileKotlin --console=plain
```

- [ ] **Step 3: Delete the listed files**

```bash
git rm modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt \
       modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/DeviceVmSupervisor.kt \
       modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/WorkspaceDeviceIdeHost.kt \
       modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImpl.kt \
       modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/DeviceProgramSupport.kt
git rm modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt 2>/dev/null || true
git rm modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/DeviceProgramSupportTest.kt 2>/dev/null || true
git rm modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImplTest.kt 2>/dev/null || true
```

- [ ] **Step 4: Build core + neoforge**

```bash
./gradlew :core:build :v1_21_1:v1_21_1-neoforge:compileKotlin --console=plain
```

Expected: green. If a test under `modules/core/src/test/` still imports a deleted class, delete that test in this commit.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "chore(ck-removal): delete BackgroundDeviceVm, RuntimeDeviceImpl, DeviceProgramSupport

Refs #26"
```

---

## Task 4: Delete `LanguageServices` and the CKL frontend

**Files:**
- Delete: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/language/LanguageServices.kt`
- Delete: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/language/` (whole package if it becomes empty)
- Delete: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/**`
- Delete: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/**`
- Delete: `modules/core/src/main/resources/rom/**` (CKL ROM scripts, if present)

- [ ] **Step 1: Inventory `lang.frontend.*` and `lang.runtime.image.*`**

```bash
ls modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend
ls modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image
rg -n 'lang\.frontend|lang\.runtime\.image|LanguageFrontend|LanguageIde|CkVmImageAbi' modules/core/src/main modules/v1_21_1/*/src/main
```

Expected: only `LanguageServices.kt` (about to be deleted) references them on the production side. If anything else does, deal with it in this task.

- [ ] **Step 2: Delete `LanguageServices.kt` and the package**

```bash
git rm modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/language/LanguageServices.kt
rmdir modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/language 2>/dev/null || true
```

If the package contains additional files, audit them and delete the CKL-bound ones. Anything not CKL-bound stays.

- [ ] **Step 3: Delete the CKL frontend and CKIM Kotlin runtime**

```bash
git rm -r modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend
git rm -r modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image
git rm -r modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend 2>/dev/null || true
git rm -r modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image 2>/dev/null || true
```

- [ ] **Step 4: Delete CKL ROM scripts if any**

```bash
ls modules/core/src/main/resources/rom 2>/dev/null
```

If present and `.ck`-shaped, `git rm -r modules/core/src/main/resources/rom`. If absent, skip.

- [ ] **Step 5: Build**

```bash
./gradlew :core:build :compiler:build --console=plain
```

Expected: green. The `:compiler` module now only contains `lang/runtime/blazing/**` (the JNI bindings) and supporting types.

- [ ] **Step 6: Verify zero CKL references**

```bash
rg -n 'LanguageFrontend|LanguageIde|CkVmImageAbi|LanguageServices' modules native
```

Expected: zero hits.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "chore(ck-removal): delete CKL frontend (LanguageFrontend, LanguageIde, CkVmImageAbi)

Refs #26"
```

---

## Task 5: Remove CKIM JNI surface in `NativeVmBindings.kt`

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
- Delete: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerTest.kt`
- Delete: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindingsImageOnlyTest.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt` — keep LowImage / RuxComputer sections, delete CKIM sections

- [ ] **Step 1: List the CKIM-only members in `NativeVmBindings`**

Open [NativeVmBindings.kt](modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt) and locate:
- `interface NativeImageVmRunner` and its default implementation (the block currently containing `runImageUntilSignal`, `resumeImageWith`, `imageMetricsNative`).
- Companion `external` declarations: `createImageNative`, `runImageUntilSignalForHandleNative`, `resumeImageWithNative`, `imageMetricsNative`, `freeImageNative`.
- Helper data classes (`NativeImageVmMetrics`, anything CKIM-specific).

Confirm nothing in this list is shared with LowImage/RuxComputer by reading the file end-to-end.

- [ ] **Step 2: Remove them**

Edit the file to delete the identified members. Keep everything related to `LowImage` (`createLowImageNative`, `runLowImageUntilSignalNative`, `lowImageMetricsNative`, `freeLowImageNative`, `NativeLowImageVmMetrics`, `NativeLowImageVmSignal`) and `RuxComputer` (`createRuxComputerNative`, `runRuxComputerUntilSignalNative`, `ruxComputerControlNative`, etc.).

- [ ] **Step 3: Delete the dedicated CKIM test files**

```bash
git rm modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunnerTest.kt
git rm modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindingsImageOnlyTest.kt
```

- [ ] **Step 4: Trim `NativeImageVmBindingsJniTest.kt`**

Open it, delete every test method that uses `createImageNative`/`runImageUntilSignal`/`resumeImageWith`/`imageMetricsNative`/`freeImageNative`. Keep `createLowImageNative`/`runLowImageUntilSignal`/`runRuxComputerUntilSignal` tests. If after trimming the file is empty, `git rm` it.

- [ ] **Step 5: Build & test the compiler module**

```bash
./gradlew :compiler:build --console=plain
```

Expected: green.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "chore(ck-removal): drop CKIM JNI methods from NativeVmBindings

Refs #26"
```

---

## Task 6: Remove CKIM in `native/rux-vm`

**Files:**
- Modify: `native/rux-vm/src/jni.rs`
- Delete: `native/rux-vm/src/image.rs`
- Delete: `native/rux-vm/src/image_runner.rs`
- Modify: `native/rux-vm/src/lib.rs`
- Audit & maybe delete: `native/rux-vm/src/value.rs`, `native/rux-vm/src/signal.rs`
- Modify: `native/rux-vm/tests/**`

- [ ] **Step 1: Classify `value.rs` and `signal.rs`**

```bash
rg -n 'value::|signal::|VmValue|decode_value|encode_value' native/rux-vm/src
```

If the only users are `image*.rs` and the CKIM JNI methods being deleted, mark both `value.rs` and `signal.rs` for deletion. If `low_image*.rs` / `computer*.rs` use them, keep them.

- [ ] **Step 2: Remove the CKIM JNI exports from `jni.rs`**

Open [jni.rs](native/rux-vm/src/jni.rs). Delete the five `extern "system"` functions:

- `Java_..._createImageNative`
- `Java_..._runImageUntilSignalForHandleNative`
- `Java_..._resumeImageWithNative`
- `Java_..._imageMetricsNative`
- `Java_..._freeImageNative`

Also delete the helper `image_handle_mut(...)` and any `byte_array_or_throw(...)`-style helpers used only by them.

Remove `use` statements that referenced `crate::image_runner`, `crate::image`, and `crate::value`/`crate::signal` if those modules are being deleted.

- [ ] **Step 3: Delete the CKIM Rust files**

```bash
git rm native/rux-vm/src/image.rs native/rux-vm/src/image_runner.rs
# Only if Step 1 marked them CKIM-only:
git rm native/rux-vm/src/value.rs
git rm native/rux-vm/src/signal.rs
```

- [ ] **Step 4: Update `lib.rs`**

Open [lib.rs](native/rux-vm/src/lib.rs) and remove the `pub mod image; pub mod image_runner;` (and `pub mod value; pub mod signal;` if deleted) lines.

- [ ] **Step 5: Remove CKIM-targeted Cargo tests**

```bash
ls native/rux-vm/tests
```

Delete tests whose names indicate CKIM coverage (`image_*`, `ckim_*`, `vm_value_*`). Keep `low_image_*`, `rux_computer_*`, etc.

- [ ] **Step 6: Build native crate**

```bash
(cd native/rux-vm && cargo build)
(cd native/rux-vm && cargo test)
```

Expected: green.

- [ ] **Step 7: End-to-end JNI build**

```bash
./gradlew :compiler:build --console=plain
```

Expected: green. The Kotlin side compiles against a JNI surface that now matches the Rust side.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "chore(ck-removal): delete CKIM VM and JNI exports from rux-vm

Refs #26"
```

---

## Task 7: Drop CKL build-system traces and assets

**Files:**
- Delete: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/bios.ck`
- Modify: `modules/compiler/build.gradle.kts`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts`

- [ ] **Step 1: Delete `bios.ck` and any other `.ck` resources**

```bash
git rm modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/bios.ck
fd -e ck . modules native 2>/dev/null | xargs -r git rm
```

If `fd` is unavailable: `find modules native -name '*.ck' -print -delete` then `git add -A`.

- [ ] **Step 2: Remove CKL benchmark/profiling system properties**

Open [modules/compiler/build.gradle.kts](modules/compiler/build.gradle.kts) and delete every `systemProperty("ckl.*", …)` line **whose benchmark target is the CKL frontend or CKIM VM**. Keep properties used by the RUXI LowImage benches if any still apply; rename them to `ruxi.*` if you keep them.

If the entire benchmark task block targets the deleted CKL benches, delete the task block.

Likewise edit [modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts](modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts) lines 115-132 (the `ckl.profiling.*` system properties) — drop them entirely (they fed `BackgroundDeviceVm`'s native profiling helper, which no longer exists).

- [ ] **Step 3: Build**

```bash
./gradlew build --console=plain
```

Expected: green.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "chore(ck-removal): drop bios.ck and CKL build-system hooks

Refs #26"
```

---

## Task 8: Rename `modules/compiler` to `modules/native-runtime`

This task is mechanical but touches many `build.gradle.kts` files. It is split out from earlier tasks so that the destructive deletes have already been merged before the rename.

**Files:**
- Rename: `modules/compiler/` → `modules/native-runtime/`
- Modify: `settings.gradle.kts`
- Modify: `modules/core/build.gradle.kts`
- Modify: `modules/v1_21_1/v1_21_1-common/build.gradle.kts`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts`
- Modify: any other build script with `projects.compiler` / `:compiler`

- [ ] **Step 1: Rename the directory**

```bash
git mv modules/compiler modules/native-runtime
```

- [ ] **Step 2: Update `settings.gradle.kts`**

Change `include(":compiler")` to `include(":native-runtime")` (and any nested `project(":compiler").projectDir = …` references).

- [ ] **Step 3: Update dependant `build.gradle.kts` files**

```bash
rg -n 'projects\.compiler|":compiler"' modules
```

Replace `projects.compiler` → `projects.nativeRuntime` and `":compiler"` → `":native-runtime"`.

- [ ] **Step 4: Update the Kotlin package?** No.

The Kotlin package stays `ru.lazyhat.compukterkraft.lang.runtime.blazing` to avoid touching every file. The Gradle module name change is sufficient for the spec's intent.

- [ ] **Step 5: Build**

```bash
./gradlew build --console=plain
```

Expected: green.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(ck-removal): rename :compiler module to :native-runtime

Module contains the JNI bridge to native/rux-vm, no compiler logic.

Refs #26"
```

---

## Task 9: Documentation refresh

**Files:**
- Modify: `docs/LANGUAGE.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/abi/CHANGELOG.md`
- Modify: `README.md`

- [ ] **Step 1: `docs/LANGUAGE.md` — replace with retirement stub**

Overwrite the file content with:

```markdown
# Language

CKL, the original Kotlin-hosted language, has been removed. Compukter-Kraft now uses **Rux**, a Rust-like language implemented in the `native/rux-compiler` crate.

- Language reference and syntax: see source in [native/rux-compiler/src/frontend/](../native/rux-compiler/src/frontend/) and examples under [native/rux-compiler/examples/](../native/rux-compiler/examples/).
- Standard library: [native/rux-compiler/stdlib/std/](../native/rux-compiler/stdlib/std/).
- Bytecode ABI (RUXI v1): [docs/abi/](abi/).

The in-game Workbench/Computer/Terminal authoring surfaces are disabled pending re-implementation on top of the Rux toolchain (tracked on the roadmap).
```

- [ ] **Step 2: `docs/ARCHITECTURE.md`**

Open the file, find the diagram / section listing `BackgroundDeviceVm`, `CkVmImageAbi`, `LanguageFrontend`, `Workbench`, `Computer block`, `Terminal`. Delete those nodes. Add or promote nodes for: `native/rux-compiler` → `rux-laptop.ruxi` → `RuxComputerRuntime` → `RuxRuntimeDevice` → `Notebook`. Update prose accordingly.

- [ ] **Step 3: `docs/abi/CHANGELOG.md`**

Append:

```markdown
## YYYY-MM-DD — CKIM retired

CKIM (the original CKL stack-VM bytecode format) and its JNI bindings have been removed from `native/rux-vm`. RUXI v1 remains the only supported bytecode. The frozen RUXI v1 spec is unchanged; no compatibility breakage for Rux-produced images.
```

(Use today's date.)

- [ ] **Step 4: `README.md`**

Find the section that mentions CKL / `.ck` / Workbench programming. Replace with a one-paragraph "Rux is the language; Notebook is the playable surface today; full editor/IDE coming back in follow-up issues".

- [ ] **Step 5: Build docs verification**

```bash
rg -n 'CKL|LanguageFrontend|Workbench|BackgroundDeviceVm|CkVmImageAbi' docs README.md
```

Expected: hits only inside `docs/superpowers/specs|plans|todos` (historical records). If any active doc still mentions them as live, fix it.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "docs(ck-removal): update language/architecture references to Rux-only

Refs #26"
```

---

## Task 10: End-to-end verification

**No file changes.** This task is a verification gate. Each step is a check.

- [ ] **Step 1: Full build, both stacks**

```bash
./gradlew clean build --console=plain
(cd native && cargo test --workspace)
```

Expected: green on both.

- [ ] **Step 2: Symbol grep**

```bash
rg -n 'LanguageFrontend|LanguageIde|CkVmImageAbi|BackgroundDeviceVm|DeviceVmSupervisor|RuntimeDeviceImpl|DeviceProgramSupport|createImageNative|runImageUntilSignalForHandleNative|resumeImageWithNative|imageMetricsNative|freeImageNative' modules native
```

Expected: zero hits (excluding `docs/superpowers/specs|plans|todos`).

- [ ] **Step 3: Asset grep**

```bash
find modules native -name '*.ck'
```

Expected: empty.

- [ ] **Step 4: Manual dev launch (Minecraft)**

```bash
./gradlew :v1_21_1:v1_21_1-neoforge:runClient
```

Inside the game: open Creative inventory → confirm **only Notebook** appears as the computer-class block (no Workbench, no standalone Computer, no Terminal item). Place a Notebook → right-click → screen opens → `rux-laptop.ruxi` boots → its UI is visible (per `RuxFirmwareResourceTest` the screen renders the laptop firmware output).

- [ ] **Step 5: Move issue to Done**

```bash
ITEM=$(gh project item-list 6 --owner lazyhat --limit 100 --format json --jq '.items[] | select(.content.number == 26) | .id')
gh project item-edit \
  --project-id PVT_kwHOBkSydc4BYgqn \
  --field-id PVTSSF_lAHOBkSydc4BYgqnzhTmClk \
  --id "$ITEM" \
  --single-select-option-id 06288b34
gh api -X PATCH /repos/lazyhat/Compukter-Kraft/issues/26 -f state=closed -f state_reason=completed
```

- [ ] **Step 6: Final commit (if any leftover docs/cleanup touched)**

```bash
git status
# If anything modified:
git add -A
git commit -m "chore(ck-removal): MVP autonomy verification gate

Refs #26"
```

---

## Notes on execution

- Tasks 1–9 each end with a passing-or-intentionally-red `./gradlew compileKotlin` and a commit. Do not stack uncommitted work across tasks.
- Task 2 contains a real branch (Step 1a vs Step 1b) that depends on inspecting `ComputerBlockEntity.kt`. Stop and inspect; do not guess.
- Task 6 contains a real audit (Step 1) on `value.rs`/`signal.rs`. Stop and grep; do not guess.
- If any task uncovers a dependency we missed (e.g., a non-CKL caller of `RuntimeDeviceImpl`), STOP, surface it, and decide whether to (a) keep the file, (b) migrate the caller to `RuxRuntimeDevice`, (c) hide the caller. Do not paper over with stubs.
