# Rust VM Prototype Design

## Purpose

Explore a Rust implementation of the CKL VM without changing CKL syntax, compiler semantics, ROM behavior, or Minecraft-side runtime ownership. The prototype should answer whether a native VM runner can reduce interpreter and chatty builtin overhead while preserving the current Kotlin compiler and runtime contracts.

The first prototype is not a full runtime rewrite. It is an alternative VM runner behind the current JVM runtime host.

## Current Context

The Kotlin `compiler` module owns the CKL frontend and produces `BytecodeModule`. The current steady-state interpreter is `BytecodeVirtualMachine`, which owns frames, value stack, heap, instruction budget handling, and `VmSignal` emission.

Runtime devices are hosted by `BackgroundDeviceVm`. The VM coroutine runs programs, while the server tick thread requests slices, drains host calls, flushes display frames, and observes VM state. Display, events, IPC, and process-local runtime state are currently owned inside the VM host, while filesystem operations intentionally cross the host-call boundary into workspace storage.

Recent profiling shows that terminal workloads are dominated more by VM/interpreter and chatty builtin behavior than by raw display frame serialization. The held-Enter profiling workload confirms backlog under repeated input without filtering Enter.

## Ownership Rings

Rustization should be planned by ownership ring rather than by a single “VM vs builtins” boundary.

### Ring 0: Pure VM Core

Rust should own this in the prototype:

- opcode dispatch;
- frames and instruction pointer management;
- value stack;
- heap/object table;
- primitive arithmetic, bitwise, comparison, and control flow;
- function calls and returns;
- instruction budget pause behavior.

This ring has the cleanest boundary and the strongest performance rationale.

### Ring 1: VM-Local Builtins and State

Rust may own selected parts of this ring after the pure VM runner works:

- string builtins such as `length`, `charAt`, `trim`, `beforeSpace`, `afterSpace`, `isBlank`, and `toInt`;
- collection methods for arrays, lists, and maps;
- event argument decoding;
- IPC channel buffers;
- possibly display framebuffer state and dirty tracking later.

These APIs are deterministic and local to one runtime device. Moving them into Rust can reduce chatty VM-to-runtime builtin calls, but it increases ownership and lifecycle complexity.

### Ring 2: Host-Bound Services

These should stay Kotlin-owned for the prototype:

- filesystem and workspace storage;
- process spawn/wait and child process management;
- system shutdown, reboot, logging, current tick, and label;
- Minecraft lifecycle, networking, menus, screens, and display session transport.

Filesystem should not be moved to Rust as a first performance optimization. It is a workspace boundary, not pure VM state. Moving it would duplicate path normalization, sandbox rules, Workbench synchronization expectations, and JVM-side storage ownership. A future read-only source snapshot or cache may be considered separately, but Rust should not become the authoritative workspace owner in this prototype.

## Recommended Prototype Boundary

The prototype should use a JNI-backed Rust VM runner with Kotlin still acting as compiler, runtime host, and owner of host-bound services.

The Kotlin side serializes `BytecodeModule` into a stable bytecode ABI. The Rust side creates a `VmInstance`, executes until a signal, and returns a compact signal/result representation to Kotlin. Kotlin resumes the Rust VM with host-call results when needed.

Conceptual native operations:

- create VM instance from serialized bytecode and profile limits;
- run until signal or instruction budget pause;
- resume with a value after a host call, sleep, yield, or event result;
- snapshot or dispose the instance;
- optionally enable diagnostic counters.

The prototype must not call Minecraft, workspace, or Kotlin runtime APIs directly from Rust. All cross-boundary interaction should happen through neutral signals and value/result payloads.

## Bytecode and Value ABI

The bytecode ABI is the central prototype artifact. It should be explicit, versioned, and independent of Kotlin data class layout.

Minimum contents:

- ABI version;
- functions with local counts, parameter counts, return type tags, and instruction streams;
- constants and strings;
- record metadata;
- class metadata, field names, init function, instance methods, and static methods;
- entry function index;
- builtin module/function identifiers.

Value representation should support:

- `Unit`;
- `Null`;
- `Bool`;
- `Int`;
- `Long`;
- `String`;
- record values;
- object references;
- arrays, lists, maps, and class instances in the Rust heap.

The first ABI can be internal and test-only, but it must still be deterministic and versioned so the prototype does not become coupled to JVM object layout.

## Builtin Ownership Plan

Initial implementation:

- Rust owns Ring 0 only.
- All `CallBuiltin` instructions become Rust-returned `HostCall` signals unless they are global VM controls such as yield/sleep.
- Kotlin executes host calls through the existing runtime bridge and resumes Rust with a `VmValue` result.

First Rust-owned builtin expansion:

- string builtins;
- collection methods;
- event argument access.

Second Rust-owned builtin expansion, only if metrics justify it:

- IPC channel buffers;
- display framebuffer state and dirty tile building.

Keep Kotlin-owned unless a separate storage/runtime design is approved:

- filesystem;
- process management;
- system and Minecraft-facing APIs.

## Testing Strategy

The prototype should be test-driven through dual-runner comparisons.

1. Pure VM parity tests:
   - compile CKL once;
   - run with Kotlin VM and Rust VM;
   - compare results, halt behavior, and error class where applicable.

2. Signal bridge tests:
   - programs that call `display.primary`, `events.tryPull`, `ipc.write`, and `filesystem.exists`;
   - assert that Rust emits the same host-call shape and resumes with the same result behavior.

3. Runtime workload tests:
   - run existing terminal profiling workload in Kotlin VM mode and Rust VM mode;
   - run held-Enter workload in both modes;
   - compare `signals`, `host-calls`, `instructions`, display metrics, and visible terminal behavior.

4. ABI compatibility tests:
   - snapshot serialized bytecode for representative programs;
   - reject unknown ABI versions;
   - validate malformed bytecode errors.

## Metrics and Decision Gates

The prototype is successful only if it provides actionable evidence.

Minimum metrics:

- Rust VM execution nanos per slice;
- signal counts by kind;
- host-call counts by module/function;
- Rust-owned builtin counts if enabled;
- JNI boundary call counts and payload sizes;
- allocation counters if feasible.

Decision gates:

1. If pure Rust VM cannot pass parity tests, stop before builtin migration.
2. If JNI payload overhead dominates, reconsider ABI batching before moving more builtins.
3. If host-call counts remain dominant, move only deterministic local builtins first.
4. If filesystem appears expensive, investigate caching or workspace batching before moving filesystem ownership.
5. If display serialization becomes dominant, consider native tile serialization separately from VM execution.

## Packaging Constraints

The prototype should assume Java 21 Minecraft runtime constraints. JNI is the practical native boundary for now. The Rust library should be optional in early development, with a Kotlin VM fallback when native loading fails or the platform is unsupported.

Native packaging must eventually account for Linux, Windows, and macOS classifiers, but the prototype can start with local developer builds only.

## Non-Goals

- Rewriting the CKL frontend in Rust.
- Moving Minecraft-facing code to Rust.
- Replacing workspace storage with Rust.
- Changing CKL language semantics.
- Filtering or changing repeated Enter behavior.
- Removing the Kotlin VM before parity and profiling evidence exists.

## Open Questions for Implementation Planning

- Exact ABI encoding format: custom binary, protobuf-like schema, or flat buffers.
- Native build integration: Gradle task layout and local developer workflow.
- Runner selection flag: profile setting, system property, or test-only constructor injection.
- Error mapping between Rust VM traps and Kotlin runtime crash reporting.
- Whether event argument payloads should move before or after string builtins.