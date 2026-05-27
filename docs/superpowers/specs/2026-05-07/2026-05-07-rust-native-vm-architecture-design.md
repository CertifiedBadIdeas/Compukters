# Rust-Native VM and Compiler Architecture Design

## Status

Draft approved for architecture exploration on 2026-05-07.

## Context

Compukter Kraft currently has a Kotlin-owned CKL frontend, a Kotlin bytecode model, a Kotlin VM, and an opt-in Rust JNI VM prototype. The existing Rust prototype intentionally mirrors the Kotlin VM boundary: Kotlin compiles a `BytecodeModule`, serializes it through `BytecodeAbi`, Rust decodes that module, executes it, and returns VM signals to Kotlin through JNI.

That compatibility-first design is useful for proving JNI execution and comparing observable behavior, but it is not a good long-term Rust architecture. The current Rust VM still inherits the Kotlin VM shape: enum-like values, object-style heap structures, string-based builtin calls, and a bytecode format optimized around Kotlin data classes rather than a Rust-owned execution image.

The strategic goal is to let Rust own the performance-critical compiler backend and VM runtime without forcing Rust to preserve the JVM VM internals.

## Decision

Use a staged architecture:

1. Keep the Kotlin frontend and IDE tooling temporarily.
2. Add a new Rust-native VM image backend beside the existing Kotlin bytecode backend.
3. Make the Rust VM execute the new image format, not the Kotlin `Instruction` model.
4. Keep the Kotlin VM as a legacy/debug/fallback runner during migration, not as a long-term equal implementation target.
5. Treat JNI as a binary protocol boundary and lifecycle boundary, not as an object-model boundary.
6. Move the full compiler frontend to Rust later, after the image format and Rust runtime model are stable.

This means JVM VM and Rust VM may coexist temporarily, but they do not need to share internal bytecode, memory layout, heap representation, or instruction dispatch strategy. They only need to preserve observable CKL semantics and the host API contract while both are supported.

## Non-Goals

- Do not optimize the current Rust prototype by copying more Kotlin VM internals.
- Do not make `BytecodeModule` the permanent Rust VM input format.
- Do not require long-term feature parity between Kotlin VM internals and Rust VM internals.
- Do not expose raw Rust VM memory pointers to Kotlin or Minecraft code.
- Do not rewrite the full parser, analyzer, IDE diagnostics, and autocomplete stack before the new runtime image is proven.

## Target Architecture

The architecture separates four concerns:

- **Language frontend:** parses CKL, resolves imports, performs semantic analysis, and produces typed language-level information.
- **Compiler backend:** lowers typed CKL into executable artifacts.
- **VM image:** a binary, Rust-native program image containing code, constants, layouts, imports, and optional debug metadata.
- **Runtime host integration:** schedules the VM, dispatches host calls, moves display/events/filesystem/peripheral data, and owns Minecraft-facing state.

The current `BytecodeModule` remains valid for the legacy Kotlin VM. The new Rust VM gets a separate artifact, tentatively named `CkVmImage`.

## `CkVmImage` Format

`CkVmImage` should be a compact executable image rather than a serialized Kotlin object graph. It should be closer to a small cartridge or ELF-like format than to the current bytecode model.

Required sections:

### Header

- Magic bytes and ABI version.
- CKL language version.
- Target VM ABI version.
- Required capabilities.
- Memory/resource assumptions derived from the device profile.

### Constant Pool

- Immutable strings.
- Numeric constants when beneficial.
- Interned names needed for diagnostics or optional reflection.
- Literal records that can be represented immutably.

Runtime instructions should reference constants by numeric id or offset, not by embedding full strings in the hot path.

### Type and Layout Section

- Struct layouts.
- Class layouts.
- Field offsets.
- Mutability metadata.
- Method table references.
- Value representation kind for each relevant type.

This section lets the VM execute field access and object construction without repeated string lookup.

### Function Table

- Entry point.
- Function code offsets.
- Parameter count.
- Local count.
- Stack/frame size metadata.
- Optional source/debug references.

### Code Section

- Compact opcodes.
- Fixed-width or varint operands chosen per ABI design.
- Numeric function/type/import references.
- No Kotlin class names or Kotlin enum serialization in the runtime hot path.

### Host Import Table

- Numeric import ids for builtins such as display, filesystem, events, IPC, process, redstone, and peripherals.
- Stable signatures for host-call arguments and results.
- Optional debug names for diagnostics.

Runtime host calls should carry import ids, not repeated module/function strings.

### Debug Section

- Source ranges.
- Symbol names.
- Line mappings.
- Optional instruction-to-source mapping.

The debug section should be optional and should not be required for normal execution.

## Rust VM Runtime Model

The Rust VM should be designed as a VM with its own memory, not as a direct translation of JVM boxed values.

### VM Memory

- Allocate a bounded VM-owned memory block or arena according to `vmRamBytes`.
- Account all VM heap allocations against this limit.
- Keep host-owned Minecraft/Kotlin state outside VM memory.
- Use handles or offsets for VM references.
- Do not expose raw VM memory pointers over JNI.

### Values

- Use compact tagged values.
- Store small values such as unit, null, booleans, integers, and longs inline when possible.
- Store strings, arrays, maps, class instances, and large records in VM-owned memory.
- Pass host-call arguments through a stable encoded value protocol, not through Rust internal pointers.

### Stack and Frames

- Store call frames in a VM-owned stack region or frame arena.
- Use compiler-provided frame metadata to size locals and operand stack regions.
- Avoid allocating per-instruction boxed frame/stack structures.

### Heap

- Start with a simple arena or bump allocator plus explicit memory accounting.
- Add free lists or compaction later only if workload evidence requires it.
- Keep object handles stable for the lifetime rules chosen by the VM.

### Constants

- Store immutable constants in a readonly image/constant region.
- Intern strings where useful.
- Use ids or offsets for repeated access.

This model can start safe and conservative, using bounds-checked slices and handle newtypes. The important design constraint is that public Rust runtime code should be organized around VM memory sections, handles, offsets, and image metadata rather than Kotlin-like boxed object structures.

## Compiler Migration Strategy

### Phase 1 — Kotlin Frontend, Rust Image Backend

- Keep Kotlin parser, analyzer, diagnostics, and IDE features.
- Add a backend that lowers typed CKL into `CkVmImage`.
- Keep the legacy bytecode backend for Kotlin VM tests and debugging.
- Add image ABI tests and backend parity tests.

### Phase 2 — Shared Typed IR

- Introduce an explicit typed semantic IR between analysis and backend lowering.
- Keep this IR language-level, not JVM-bytecode-level.
- Generate both legacy `BytecodeModule` and `CkVmImage` from this IR while migration is active.

### Phase 3 — Rust Compiler Libraries

- Move parser, semantic analysis, and code generation into Rust libraries incrementally.
- Expose compiler services to Kotlin through a binary API suitable for Workbench IDE features.
- Return diagnostics, symbol data, autocomplete data, and compiled images through stable encoded data structures.

### Phase 4 — Rust-Only Toolchain

- Make Rust compiler and Rust VM the source of truth.
- Remove or freeze the Kotlin VM after migration confidence is high.
- Keep conformance tests as the long-term compatibility guard for CKL semantics.

## JNI and Runtime Boundary

JNI should expose lifecycle operations and binary protocol messages, not Kotlin VM objects.

Runtime operations:

- `createVm(imageBytes, runtimeConfigBytes) -> handle`
- `runSlice(handle, budget) -> signalBytes`
- `resume(handle, valueBytes) -> statusBytes`
- `snapshot(handle, snapshotKind) -> snapshotBytes`
- `free(handle)`

Future compiler operations:

- `compileSource(sourceBundleBytes, targetProfileBytes) -> compilerResultBytes`
- `queryIde(sourceBundleBytes, queryBytes) -> ideResultBytes`

Signal protocol:

- `Pause`
- `Yield`
- `Sleep(ticks)`
- `WaitEvent(filter)`
- `HostCall(importId, arguments)`
- `Halt(result)`
- `Trap(error)`

Host calls should use stable numeric import ids. Kotlin can map those ids back to the existing host APIs. Debug builds may include names for reports and diagnostics, but names should not be required in the hot path.

## Coexistence With Kotlin VM

The Kotlin VM can coexist as a fallback while migration is active. Coexistence should be based on an outer program lifecycle contract, not on shared internals.

The common contract is:

1. Load a compiled artifact.
2. Run until a VM signal.
3. Dispatch host calls and scheduling signals.
4. Resume with host results or events.
5. Stop and free resources.
6. Expose diagnostics and profiling data.

The compiled artifact type may differ by runner. Kotlin VM can consume `BytecodeModule`; Rust VM should consume `CkVmImage`. A future abstraction can represent this as a `CompiledProgramArtifact` with explicit target kind.

## Testing Strategy

### Image ABI Tests

- Validate section encoding and decoding.
- Validate version and capability rejection.
- Validate endian behavior and malformed input handling.

### Backend Parity Tests

- Compile the same CKL sources through legacy bytecode and new image backend.
- Compare observable behavior for deterministic programs.
- Compare emitted host calls for host-bound programs.

### VM Conformance Tests

- Arithmetic and bitwise operations.
- Control flow.
- Function calls.
- Records and classes.
- Arrays, lists, and maps.
- Null behavior.
- String behavior and UTF-8 boundary cases.

### Host Protocol Tests

- Verify every builtin has a stable import id and signature.
- Verify argument and result encoding for all supported value kinds.
- Verify unsupported host result values fail with deterministic traps.

### Memory Limit Tests

- Verify VM allocations respect `vmRamBytes`.
- Verify out-of-memory traps are deterministic.
- Verify host state is not counted as VM-owned memory unless explicitly copied into VM memory.

### Scheduling Tests

- Verify `Pause`, `Yield`, `Sleep`, and `WaitEvent` behavior with `BackgroundDeviceVm` scheduling.
- Verify run-slice granularity does not break runtime event ordering.

### Profiling Tests

- Keep the isolated JVM/Rust comparison report during migration.
- Add image-runner counters for executed instructions, allocations, memory usage, host imports, traps, and run-slice timing.
- Separate semantic conformance from performance comparisons and integration scheduling diagnostics.

## Risks

### Workbench IDE Coupling

The Workbench depends on compiler frontend services. Rewriting the whole compiler first would risk losing diagnostics and autocomplete for a long period. This is why the first step keeps Kotlin frontend services and changes the backend/runtime boundary first.

### ABI Lock-In

The first image ABI may become hard to change once ROM programs and tests depend on it. The image format must include explicit versioning and capability negotiation from the start.

### Host-Call Overhead

JNI and host-call dispatch can dominate workloads that call display, filesystem, IPC, or events frequently. The new image format improves VM-side execution but does not automatically remove host overhead. Profiling must separate VM instruction cost from host-call cost.

### Scheduling Differences

A faster VM can reach host calls, pauses, and event waits at different times. Integration profiling must warn about unequal progress and should not interpret all timing ratios as equal-work speedups.

### Memory Safety and Debuggability

Using a VM-owned memory block increases architectural flexibility but also increases risk. The first implementation should prefer handle newtypes, bounds-checked memory access, and deterministic traps over unsafe optimizations.

## Open Follow-Up Decisions

These decisions should be made in a later implementation plan or smaller design specs:

- Exact `CkVmImage` binary encoding.
- Fixed-width versus varint operands.
- Exact tagged value bit layout.
- Initial allocator strategy.
- Garbage collection policy, if any.
- Host import id registry format.
- Shape of the shared typed IR.
- Kotlin API for `CompiledProgramArtifact`.
- Rust compiler API for IDE queries.

## Success Criteria

- Rust VM executes a Rust-native image without depending on Kotlin `Instruction` internals.
- VM memory allocation is bounded by the device profile.
- Host calls cross JNI through stable import ids and encoded values.
- Legacy Kotlin VM can remain as a temporary fallback without constraining Rust internals.
- Conformance tests prove observable CKL semantics across the migration period.
- Profiling distinguishes VM execution, host-call overhead, memory allocation, and scheduling effects.