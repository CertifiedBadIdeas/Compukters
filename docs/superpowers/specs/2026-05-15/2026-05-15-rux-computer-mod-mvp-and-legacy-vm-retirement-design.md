# Rux Computer Mod MVP And Legacy VM Retirement Design

## Goal

Make Rux the forward runtime path for computers in the mod by booting a `RUXI` firmware image on the Rust `ComputerMachine`, then retire the legacy CKL VM and Kotlin fallback paths in controlled fail-fast slices.

The first milestone is deliberately small: a single Rux firmware program runs on a computer-class machine, writes visible output through the existing debug serial MMIO channel, reports control status, and can be driven from the mod runtime without falling back to the old Kotlin VM.

## Context

`RUXI` low image ABI version `1` is frozen. Future language growth must lower to the frozen image format unless a new image ABI version is explicitly introduced.

The machine profile is not frozen yet. That is useful: the runtime still needs a real computer-class profile for the mod, including RAM size, MMIO device ranges, boot lifecycle, execution budgets, and terminal/display/input bridges.

The repository currently has two runtime families:

- legacy CKL image/runtime paths, including old VM concepts and tests;
- Rux low VM paths, including `rux-compiler`, `rux-vm`, `ComputerMachine`, `MachineBus`, `RUXI` fixtures, and a small terminal runner.

The direction is to make the Rux path production-facing and remove old VM fallback behavior instead of preserving compatibility layers indefinitely.

## Non-Goals

- Do not change `RUXI` image ABI v1.
- Do not add a Rux OS, process table, virtual memory, filesystem, or shell in this milestone.
- Do not stabilize the full display/input MMIO map yet.
- Do not keep Kotlin VM fallback paths for the new Rux computer runtime.
- Do not rewrite the whole Kotlin mod integration in one step.
- Do not delete legacy CKL code before the Rux boot path is exercised by tests.

## Approach

Use a vertical slice:

```text
Rux source or bundled RUXI firmware
  -> RUXI v1 image bytes
  -> JNI/native Rust bridge
  -> ComputerMachine
  -> boot CPU
  -> shared linear RAM + MMIO
  -> control/debug output
  -> Kotlin/mod terminal presentation
```

The first mod-facing version should use precompiled `.ruxi` resources. That avoids shipping or invoking the Rux compiler inside the game runtime too early. The compiler remains available as a build/tooling step, and the mod runtime only needs to load and run stable image bytes.

## Runtime Ownership

The Rust side owns:

- decoding `RUXI` images;
- constructing `ComputerMachine`;
- loading image sections into machine RAM;
- spawning the boot CPU;
- executing time-sliced VM work;
- exposing control status, exit code, panic code, and debug output;
- enforcing fail-fast behavior when the Rux image or machine setup is invalid.

The Kotlin side owns:

- selecting the bundled firmware resource;
- passing image bytes to native bindings;
- attaching the native computer handle to the mod computer lifecycle;
- polling or receiving runtime state for UI/debug presentation;
- rendering the temporary terminal view from debug serial output;
- failing loudly when the native library or Rux machine path is unavailable.

There should be no Kotlin implementation of the Rux VM and no fallback execution mode for Rux firmware.

## Native Binding Shape

The existing low image JNI functions run `LowImageVm` directly. The mod computer path should get a separate computer-machine binding so it can expose machine-level state instead of only scalar VM signals.

Suggested native API:

```text
createRuxComputerNative(imageBytes, memorySize, sliceBudgetNanos) -> handle
runRuxComputerSliceNative(handle) -> signal/status array
ruxComputerControlNative(handle) -> [status, exitCode, panicCode]
ruxComputerDebugOutputNative(handle) -> byte[]
freeRuxComputerNative(handle)
```

The handle owns a `ComputerMachine` and its boot CPU id. Running a slice resumes the same machine and CPU. A runtime fault marks the control device as `PANIC` and returns an error to Kotlin.

This API should not reuse legacy CKL image handles. Keeping a separate handle type makes accidental fallback or mixed runtime state much harder.

## Firmware Resource Flow

The MVP should include one bundled Rux firmware:

```text
native/rux-compiler/examples/firmware/terminal.rx
  -> compiled at build/test time
  -> bundled as a .ruxi resource
  -> loaded by the mod runtime
  -> booted by ComputerMachine
```

The firmware can initially write a recognizable line such as `RUX READY` to debug serial and mark the control device as `READY` before halting.

Later, this can evolve into a boot ROM and a real terminal/display driver, but the first milestone should prove only that a Rux image can boot inside the mod runtime.

## Machine Profile Boundary

The image ABI remains frozen, but the computer machine profile remains pre-freeze until the mod path proves the device contract.

For this milestone, the de facto profile is:

- one byte-addressed linear RAM space;
- little-endian unaligned loads and stores;
- one boot CPU;
- implementation-defined slice budget;
- control MMIO at `0x1000_0000`;
- debug serial MMIO at `0x1000_0100`;
- no stable terminal/display/input MMIO yet.

The profile document should continue to say pre-freeze until display/input and lifecycle behavior are known enough to support external code.

## Language Growth Strategy

Language work should stay source-level unless a missing capability truly requires a new image ABI.

Good next Rux language extensions:

- `for` range loops lowered to existing branches and arithmetic;
- `std::io::write_str` or `println`-style helpers lowered to debug serial writes;
- better diagnostics for pointer, array, and unsafe errors;
- small stdlib modules for memory and terminal-oriented output;
- optional source-level `i64`/`u64` ergonomics if the compiler surface is still incomplete.

Avoid adding new VM instructions just for syntax convenience. The frozen `RUXI` v1 instruction set is already the compatibility target.

## Legacy VM Retirement

Retirement should happen by making the new path first-class, then deleting legacy code in narrow commits.

Phase 1: identify active entry points.

- Find Kotlin runtime selectors, native binding wrappers, old CKL image runners, and tests that still imply fallback behavior.
- Classify them as production path, benchmark-only, test fixture, or dead code.

Phase 2: fail-fast new runtime path.

- Rux computer creation must fail if native bindings are missing.
- Rux firmware must fail if image decoding or machine creation fails.
- No Kotlin fallback is allowed for Rux firmware.

Phase 3: replace mod computer boot.

- Add a Rux boot path behind the current computer lifecycle.
- Keep old CKL code reachable only where existing tests still require it.
- Once the Rux path boots in the mod test suite, remove the old production selector.

Phase 4: delete dead fallback code.

- Remove unused Kotlin VM fallback classes and tests.
- Remove old property gates that select between old and new VM paths.
- Keep benchmark or historical code only if it is explicitly named as benchmark-only.

## Testing

The implementation should add tests in this order:

1. Rust `ComputerMachine` test boots the terminal firmware image and observes `READY`, debug output, and final halt state.
2. JNI/native binding test creates a Rux computer from `.ruxi` bytes and reads control/debug state.
3. Kotlin test loads the bundled Rux firmware resource and verifies fail-fast behavior when native setup fails.
4. NeoForge/runtime test boots a computer with the Rux firmware and observes terminal-visible output or debug state.
5. Search-based regression check proves there is no Kotlin fallback for Rux firmware execution.

Existing `RUXI` ABI fixtures remain unchanged.

## Migration Risks

Native library loading is the highest integration risk. The Rux computer path should use the existing production native library packaging flow and fail with a clear error if the library is unavailable.

Resource generation is the second risk. The build should avoid requiring the Rux compiler at game runtime. Firmware compilation belongs in tests or build tooling, while the mod loads committed or generated `.ruxi` bytes.

Legacy code deletion is the third risk. The old VM should not be removed before the Rux boot path has mod-level coverage, otherwise failures become hard to distinguish from migration noise.

## Success Criteria

- A bundled Rux firmware image boots on `ComputerMachine` from the mod runtime.
- The firmware can write visible debug terminal output.
- The machine reports status, exit code, and panic code through native bindings.
- Missing native support fails fast instead of falling back to Kotlin VM execution.
- `RUXI` v1 ABI docs, fixtures, and conformance tests remain unchanged.
- The next cleanup slice has a concrete list of legacy CKL/Kotlin VM entry points to delete or quarantine.
