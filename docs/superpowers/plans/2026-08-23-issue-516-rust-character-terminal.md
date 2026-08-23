# Rust Character Terminal Implementation Plan

> Issue: [#516](https://github.com/CertifiedBadIdeas/Compukters/issues/516)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the transcript prototype with a Rust-owned 51x19 Unicode character terminal reached through JDK 25 FFM and replicated to every viewer.

**Architecture:** First replace JNI with a versioned C ABI while preserving the fixture. Then make a long-lived Rust `ComputerMachine` own its execution session and retained terminal; Kotlin only adapts bounded FFM results and Minecraft clients render non-authoritative cell replicas with `minecraft:uniform`.

**Tech Stack:** Rust 2021 `cdylib`, Kotlin 2.4/JVM 25, `java.lang.foreign`, Minecraft 26.1.2, NeoForge 26.1.2.97, Rust/Kotlin tests and GameTest.

---

### Task 1: Replace JNI with FFM

**Files:**
- Rename: `host/compukter-jni` -> `host/compukter-ffi`
- Create: `host/compukter-ffi/src/ffi_api.rs`
- Delete: `host/compukter-ffi/src/jni_api.rs`
- Modify: `host/compukter-ffi/src/bridge.rs`, `host/compukter-ffi/src/wire.rs`, `host/compukter-ffi/src/lib.rs`, `host/compukter-ffi/Cargo.toml`
- Create: `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukters/lang/runtime/vm/FfmBridge.kt`
- Modify: `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukters/lang/runtime/vm/NativeRuntimeLoader.kt`
- Modify: `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukters/lang/runtime/vm/VmSession.kt`
- Modify: `modules/native-runtime/build.gradle.kts`, `modules/core/build.gradle.kts`, `modules/playground/build.gradle.kts`
- Test: `modules/native-runtime/src/test/kotlin/ru/lazyhat/compukters/lang/runtime/vm/VmSessionTest.kt`
- Test: `modules/native-runtime/src/test/kotlin/ru/lazyhat/compukters/lang/runtime/integration/FfmRuntimeIntegrationTest.kt`

- [x] **Step 1: Write failing C ABI tests**

Test ABI version, create/advance/resume/close, null pointer with non-zero
length, excessive length, short output buffer, stale handle, exact written
length, and panic containment. Use opaque handles and caller-owned output:

```rust
let mut output = [0_u8; 9];
let mut written = 0_usize;
assert_eq!(Status::Ok, unsafe {
    compukter_create(
        bytes.as_ptr(), bytes.len(), output.as_mut_ptr(), output.len(), &mut written,
    )
});
assert_eq!(9, written);
```

- [x] **Step 2: Run the missing-export failure**

Run: `cargo test --manifest-path host/compukter-ffi/Cargo.toml --locked --offline`

Expected: compile failure until the rename and `extern "C"` exports exist.

- [x] **Step 3: Implement the C ABI**

Export `compukter_abi_version`, create, advance, resume-unit/string/failure,
and close. Use fixed-width scalars, pointer/length input, and
buffer/capacity/written output. Return stable `#[repr(i32)] Status`; validate
before constructing slices, catch unwinds, and never return Rust-owned pointers.

- [x] **Step 4: Implement and test cached FFM downcalls**

Load the extracted library with `SymbolLookup.libraryLookup`, retain its arena,
cache exact `MethodHandle`s, and use confined `MemorySegment` buffers. Preserve
the existing `VmSession` contract and terminal fixture result.

- [x] **Step 5: Enforce native access**

Add `--enable-native-access=ALL-UNNAMED` and
`--illegal-native-access=deny` to native tests, playground, and NeoForge dev
runs. Fail startup with a bounded diagnostic when the runtime module lacks
native access.

- [x] **Step 6: Verify and commit**

Run: `./gradlew-sandbox-dev-parallel :native-runtime:verifyNativeRuntime :playground:endToEndTest --rerun-tasks`

Expected: fixture paths use FFM and active JNI exports are absent.

```bash
git add host modules build.gradle.kts build-scripts
git commit -m "refactor(native): replace JNI with JDK 25 FFM (#516)"
```

### Task 2: Build the Rust Terminal Device

**Files:**
- Create: `host/compukter-vm/src/terminal/{mod.rs,state.rs,input.rs,replication.rs}`
- Modify: `host/compukter-vm/src/lib.rs`
- Test: `host/compukter-vm/tests/terminal_device.rs`

- [ ] **Step 1: Write failing state tests**

Cover a blank 51x19 grid, zero-based bounds, Unicode scalar validation,
16-color cells, cursor, fill, patch, wrapping, newline, ring-row scrolling,
and UTF-16 replacement.

```rust
terminal.set_cursor(TerminalPosition::new(50, 18).unwrap());
terminal.write_utf16(&['A' as u16, 'B' as u16]).unwrap();
assert_eq!('B' as u32, terminal.cell(0, 18).unwrap().code_point());
```

- [ ] **Step 2: Run the missing-type failure**

Run: `cargo test --manifest-path host/compukter-vm/Cargo.toml --locked --offline --test terminal_device`

Expected: compile failure for the new public terminal types.

- [ ] **Step 3: Implement synchronous retained state**

Use `Cell { code_point: u32, foreground: u8, background: u8 }`, exactly 969
cells, current colors, cursor, and an internal row head. Mutations, dimension
queries, and polling are synchronous; large writes consume bounded interpreter
slices without exposing guest `suspend`.

- [ ] **Step 4: Add and implement input/replication tests**

Test stable keys, Press/Repeat, atomic text, FIFO merge, bounded rejection,
one committed revision per batch, coalesced patches, ordered scroll changes,
unchanged responses, and resync after journal eviction. Implement:

```rust
pub enum TerminalChange {
    Patch { start: u16, cells: Box<[Cell]> },
    Fill { x: u16, y: u16, width: u16, height: u16, cell: Cell },
    Scroll { rows: u16, fill: Cell },
    Cursor { position: TerminalPosition, visible: bool },
    Reset,
}
```

Waiting on an absent event is the only suspending terminal operation.

- [ ] **Step 5: Verify and commit**

Run: `cargo test --manifest-path host/compukter-vm/Cargo.toml --locked --offline`

```bash
git add host/compukter-vm
git commit -m "feat(terminal): add retained Rust character device (#516)"
```

### Task 3: Add Long-Lived `ComputerMachine` and FFM Terminal Calls

**Files:**
- Create: `host/compukter-vm/src/computer.rs`
- Modify: `host/compukter-vm/src/lib.rs`
- Modify: `host/compukter-ffi/src/{bridge.rs,ffi_api.rs,wire.rs}`
- Create: `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukters/lang/runtime/vm/TerminalModels.kt`
- Modify: `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukters/lang/runtime/vm/FfmBridge.kt`
- Modify: `modules/native-runtime/src/main/kotlin/ru/lazyhat/compukters/lang/runtime/vm/VmSession.kt`
- Test: `modules/native-runtime/src/test/kotlin/ru/lazyhat/compukters/lang/runtime/vm/VmSessionTest.kt`

- [ ] **Step 1: Write failing ownership tests**

Run the existing artifact through `ComputerMachine`; prove print/println mutate
Rust cells, readln yields `WaitingForLine`, compatibility input resumes it,
halt preserves cells, and constructing a replacement machine starts blank.

- [ ] **Step 2: Implement computer-level ownership**

Own `Session`, `TerminalDevice`, and pending compatibility read together. Move
the `compukter:terminal/1.0` binding out of FFI, consume its three fixture
operations in Rust, expose unrelated addon host requests, and retain a halted
machine until reboot/close.

- [ ] **Step 3: Extend FFM with bounded terminal data**

Add full-state and changes-since output functions plus commit, key, text, and
compatibility-line inputs. Decode immutable Kotlin `TerminalCell`, full state,
delta/change, stable key, action, and modifier models; reject trailing bytes
and invalid count products.

- [ ] **Step 4: Verify and commit**

Run both Rust crate suites and `:native-runtime:verifyNativeRuntime`.

```bash
git add host modules/native-runtime
git commit -m "feat(vm): own terminal for computer lifetime (#516)"
```

### Task 4: Remove Kotlin Terminal Authority

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukters/core/device/runtime/program/ProgramVmSession.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukters/core/device/runtime/program/ProgramRuntimeHost.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukters/core/device/computer/ProgramComputer.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukters/core/device/computer/ProgramComputerPorts.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukters/core/device/runtime/program/ProgramRuntimeHostTest.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukters/core/device/computer/ProgramComputerTest.kt`
- Modify: `modules/v26_1/v26_1-common/src/main/kotlin/ru/lazyhat/compukters/minecraft/computer/ComputerCarrier.kt`
- Modify: `modules/v26_1/v26_1-common/src/main/kotlin/ru/lazyhat/compukters/minecraft/computer/ComputerBlockEntity.kt`
- Delete: `modules/v26_1/v26_1-common/src/main/kotlin/ru/lazyhat/compukters/minecraft/computer/TerminalTranscript.kt`
- Delete: `modules/v26_1/v26_1-common/src/test/kotlin/ru/lazyhat/compukters/minecraft/computer/TerminalTranscriptTest.kt`

- [ ] **Step 1: Write failing lifecycle/facade tests**

Assert one terminal commit per server tick, full/delta delegation, screen
preservation after halt, input forwarding without Kotlin echo, and blank state
after install/reboot creates a new machine.

- [ ] **Step 2: Replace output plumbing**

Delete `StringBuilder`, `drainOutput`, `ProgramTerminalSink`, transcript state,
and Kotlin terminal capability dispatch. Forward terminal state/input through
the FFM machine and do not close it on ordinary halt.

- [ ] **Step 3: Verify and commit**

Run: `./gradlew-sandbox-dev-parallel :core:test :v26_1-common:test --rerun-tasks`

```bash
git add modules/core modules/v26_1/v26_1-common
git commit -m "refactor(core): source terminal state from Rust (#516)"
```

### Task 5: Replicate Cells and Merge Viewer Input

**Files:**
- Modify: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/terminal/TerminalPayloads.kt`
- Modify: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/terminal/TerminalNetwork.kt`
- Modify: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/terminal/TerminalClientNetwork.kt`
- Create: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/terminal/TerminalReplica.kt`
- Create: `modules/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/terminal/TerminalPayloadsTest.kt`
- Create: `modules/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/terminal/TerminalReplicaTest.kt`

- [ ] **Step 1: Write failing codec/replica tests**

Cover bounded full/delta round trips, revision mismatch, atomic application,
scroll-before-patch order, invalid scalar/palette/rectangle/count rejection,
stable key mapping, and atomic text packets.

- [ ] **Step 2: Implement versioned payloads**

Replace transcript payloads with full state, delta, resync, key, and text
payloads. Include block/machine identity and validate all products before
allocation.

- [ ] **Step 3: Implement server ordering**

Send full state on open, deltas for matching revisions, nothing when unchanged,
and full resync on mismatch. Accept bounded/rate-limited input from every valid
in-range viewer on the server thread; remove leases and optimistic echo.

- [ ] **Step 4: Verify and commit**

Run: `./gradlew-sandbox-dev-parallel :v26_1-neoforge:test --rerun-tasks`

```bash
git add modules/v26_1/v26_1-neoforge
git commit -m "feat(terminal): replicate cells and shared input (#516)"
```

### Task 6: Render the Character Grid and Remove the Prototype

**Files:**
- Modify: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/terminal/TerminalScreen.kt`
- Modify: `modules/v26_1/v26_1-neoforge/src/gameTest/kotlin/ru/lazyhat/compukters/impl/computer/ComputerBlockGameTest.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukters/core/architecture/LegacyImplementationRemovalTest.kt`
- Modify: `docs/ARCHITECTURE.md`
- Create: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/terminal/TerminalRenderGeometry.kt`
- Create: `modules/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/terminal/TerminalRenderGeometryTest.kt`

- [ ] **Step 1: Test fixed rendering math**

Prove every coordinate maps to one fixed cell, resizing does not alter 51x19,
glyph clipping stays inside a cell, palette mapping is exact, and cursor blink
does not mutate the replica.

- [ ] **Step 2: Replace transcript rendering/input**

Remove `EditBox`, `Font.split`, proportional wrapping, and polling strings.
Render background runs, fixed-origin `minecraft:uniform` glyphs, and local
cursor blink. Map key press/repeat to stable keys and `charTyped`/paste to text;
never derive printable text from GLFW letter codes.

- [ ] **Step 3: Update tests, docs, and removal guards**

Cover reboot reset in GameTest. Guard against transcript state/payloads, JNI,
leases, framebuffer/draw-list terminal code, and restored CC:Tweaked, K16,
RISC-V, or UI DSL paths. Document FFM and shell/std ownership.

- [ ] **Step 4: Verify, manually inspect, and commit**

Run: `./gradlew-sandbox-dev-parallel :v26_1-neoforge:runGameTestServer verifyLocalFull --rerun-tasks`

Run: `git diff --check`

Manually verify two viewers see identical cells and both can type; halt retains
the screen and reboot clears it.

```bash
git add modules docs/ARCHITECTURE.md
git commit -m "feat(terminal): render shared Rust character device (#516)"
```

## Self-Review

- FFM is proven on the existing fixture before terminal ABI expansion.
- Accepted terminal, input, rendering, lifecycle, and replication rules map to
  explicit tasks; complete shell/std implementation remains out of scope.
- Native buffers are caller-owned and bounded; no Rust pointer escapes.
- Every stage begins with a focused failing test and ends with verification and
  a small commit.
