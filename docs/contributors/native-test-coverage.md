---
layout: default
title: Native runtime test coverage
section: contributors
permalink: /NATIVE-TEST-COVERAGE/
---

# Native runtime test coverage

This matrix records the direct test ownership of the managed Rust runtime in
`host/compukter-vm`, its `ffi` workspace member, and the cross-layer checks that exercise the native runtime from
Kotlin and Minecraft. It is a semantic coverage inventory, not a line-coverage percentage. Update it when a runtime
boundary is added, removed, or assigned to a different verification task.

## Evidence layers

The layers below answer different questions and must not be counted as substitutes for one another.

| Layer | Primary entry point | What it establishes |
| --- | --- | --- |
| Direct Rust invariants | `cargo test --manifest-path host/compukter-vm/Cargo.toml --workspace --locked --offline` | Artifact parsing and verification, VM execution and memory invariants, device models, filesystem semantics and persistence, C ABI wire encoding, handle lifetime, and tooling behavior |
| Kotlin-to-VM conformance | `./gradlew-sandbox-dev-parallel-summary verifyKotlinVmConformance` | Compiler-produced `.cpkt` artifacts execute with the expected semantics in the pinned Rust VM |
| Portable Kotlin contracts | `./gradlew-sandbox-dev-parallel-summary :native-runtime-api:check` | Shared models, wire decoding, typed errors, loader admission and ownership, independent of a transport |
| JDK FFM integration | `./gradlew-sandbox-dev-parallel-summary :native-runtime-ffm:check` | JDK 25 descriptors, native loading and real calls into the built Rust library |
| Java 21 JNI integration | `./gradlew-sandbox-dev-parallel-summary :native-runtime-jni:check` | JNI entry points, native loading and the same session, filesystem and checkpoint contracts |
| Runtime-host integration | `./gradlew-sandbox-dev-parallel-summary :core:programRuntimeIntegrationTest` | The loader-independent host drives boot, processes, terminal input, compiler requests, deployment, and persistent files through FFM |
| Minecraft integration | `./gradlew-sandbox-dev-parallel :v26_1-neoforge:runGameTestServer` | NeoForge lifecycle, world store ownership, reboot/reload, compiler routing, IDE file access, resource snapshots, tombstones, and redstone behavior use the production runtime path |

`verifyLocalFull` composes these layers, but its success is one aggregate result rather than additional independent
coverage.

## Direct Rust matrix

| Runtime surface | Direct evidence | Principal guarantees | Remaining boundary |
| --- | --- | --- | --- |
| Artifact container, records, indexed tables, and canonical encoding | Unit tests under `src/decode`, `src/bytes.rs`, and `src/test_encode.rs`; `tests/container_rejection.rs`, `tests/minimal_artifact.rs`, `tests/golden_fixtures.rs`, and `tests/bounded_failures.rs` | Canonical forms, digests, section scope/order, record shapes, size/count limits, mutation robustness, and committed fixture reproducibility | Compiler agreement belongs to conformance rather than these decoder tests |
| Semantic verification and admission | `src/verify/tests.rs`, `src/execution/image.rs`, `tests/semantic_verification.rs`, and `tests/session_api.rs` | CFG typing, initialization, calls, exceptions, module linking, feature bits, capability compatibility, entry resolution, and profile admission atomicity | Kotlin source support is tracked by compiler tests and conformance |
| Scalar execution, control flow, calls, frames, quotas, tasks, channels, and traces | `src/execution/tests.rs`, `channel.rs`, `task.rs`, `frame.rs`, `numeric.rs`, `layout.rs`, and `error.rs` | JVM-compatible scalar behavior, deterministic block charging, calls/returns, frame bounds, stack overflow, bounded FIFO channel handoff, task wake order, trace digests, terminal outcomes, and failure payload bounds | Hardware timing is intentionally excluded from semantic gates |
| Managed heap, GC, strings, and external roots | `heap_tests.rs`, `gc_tests.rs`, `text_tests.rs`, and `external_roots.rs` | Portable layouts, allocation atomicity, arrays/objects/statics, exact safepoint roots, collection, OOM retry, UTF-16 semantics, stale handles, and steady-state allocation invariants | Hardware-specific baselines are opt-in ignored tests |
| Host requests, capabilities, suspension, and batching | `requests_tests.rs`, `session_tests.rs`, and the vertical tests in `computer.rs` | Bounded request storage, task/request identity, LWW reduction, out-of-order completion, capability admission, wait/resume atomicity, string transport, and host-failure classification | Cooperative task execution is covered by execution tests; transport tests establish request ownership and suspension |
| Terminal, stdio, and redstone devices | `tests/terminal_device.rs`, unit tests in `src/stdio.rs` and `src/redstone.rs`, plus `computer.rs` vertical tests | Grid mutation, replication/resync, bounded input, canonical/raw ownership, Unicode handling, redstone packets, waits, and output merging | Minecraft block-direction mapping belongs to GameTest |
| In-memory filesystem, paths, authority, ROM, and quota | `tests/filesystem_memory.rs`, `filesystem_namespace.rs`, `filesystem_path.rs`, and `filesystem_rom.rs` | Path canonicality, rights narrowing, isolated mounts, atomic mutations, generations, handles, logical quota, immutable ROM, and admission bounds | Host persistence is covered by the store/recovery rows below |
| Persistence codecs and logical recovery | `tests/filesystem_recovery.rs` | Exact journal/checkpoint codecs, bounded recovery, confirmed generations, torn unconfirmed tails, gaps, identity mismatch, and corruption rejection | Physical publication and process-exit behavior belong to store/crash tests; execution restoration is described below |
| World store, worker, object collection, and ownership | `tests/filesystem_store.rs`, `filesystem_store_lock.rs`, worker unit tests, and the `persistence-crash-fixture` subprocess matrix | Durable reopen, ordered mutation replay, backpressure, I/O fault degradation, tombstones, object validation/collection, close ordering, live exclusivity, stale-lock recovery, and mutation/tombstone/collection process-exit points | Process exit does not simulate storage-device loss of unsynchronized writes |
| Process, compilation, and deployment integration | `tests/process_contract.rs`, `tests/computer_machine.rs`, and `computer.rs` vertical tests | Bounded arguments/diagnostics, child completion, compiler transaction atomicity, candidate identity and retry, executable revisions, and boot behavior | End-to-end compiler and host orchestration belongs to conformance and runtime-host integration |

Logical computer checkpoints preserve execution as well as the filesystem. `src/checkpoint`,
`src/execution/checkpoint`, computer tests and the FFI tests own bounded codecs, admitted restoration and atomic
publication. Separate-process and publication crash checks are described in the pinned VM
`docs/architecture/computer-checkpoints.md`. JVM evidence includes `FfmCheckpointIntegrationTest` and
`JniCheckpointIntegrationTest`; Minecraft hibernation scenarios exercise unload, restore and fresh-boot fallback.
See [execution hibernation](architecture/persistence.md#execution-hibernation) for the production ownership contract.

## FFI matrix

| FFI surface | Rust evidence | JVM evidence | Assessment |
| --- | --- | --- | --- |
| Wire versions, tags, scalar encodings, and bounded buffers | `ffi/src/wire.rs` unit tests and `ffi/tests/ffi_api.rs` | Decoder and malformed-wire tests in `VmSessionTest`, `WorldFileSystemStoreTest`, and terminal transport tests | Strong for currently exercised values and failure tags |
| Opaque sessions, stores, and deployment candidates | `ffi/src/handle_table.rs`, bridge unit tests, and `ffi/tests/ffi_api.rs` | `VmSessionTest`, store tests, and real FFM integration tests | Covers invalid/stale/busy handles, conditional consumption, close order, and retry behavior |
| Exported artifact, execution, terminal, filesystem, deployment, compilation, redstone, and resource calls | `ffi/tests/ffi_api.rs` exercises the public session, store and checkpoint exports; bridge/session tests add deeper resume behavior | `FfmAbiParityIntegrationTest` resolves and safely invokes the authoritative descriptors for the current symbol inventory; native integration, runtime-host integration, JNI integration and GameTest additionally exercise shipped workflows | Complete built-library symbol/descriptor parity, with deeper behavior assigned to the owning Rust and vertical tests |
| Panic containment and public error classification | `ffi_api::tests::panic_is_contained_as_an_internal_status`, wire-code tests, and API invalid-input cases | Typed Kotlin mapping and malformed-result tests | Stable mappings are covered; artificial invalid addresses are outside the safe test contract, while null/length validation is covered |
| Built dynamic library and packaged loading | Runtime-bundler smoke helper plus archive tests | The shared `FfmAbiFunction` inventory, `FfmAbiParityIntegrationTest`, `nativeIntegrationTest`, `packagedNativeIntegrationTest`, loader tests, and production archive verification | Actual loading, ABI version, the current descriptors, packaged extraction, and archive composition are covered |

## Cross-layer map

The scenarios registered by `registerKotlinVmConformance` in the root `build.gradle.kts` cover compiler-produced
artifacts for the supported language and library surfaces: control flow, tasks, objects, arrays, collections, math,
geometry, callbacks and host operations. Low-level VM channel instructions remain internal infrastructure; they
do not imply a public Guest channel API. The [Kotlin support](../developers/kotlin-support.md),
[stdlib support](../developers/stdlib-support.md) and [Guest platform](../developers/guest-platform-support.md)
inventories identify each feature's exact conformance evidence. These prove compiler/runtime agreement; they do not add independent decoder,
verifier, allocator, or failure-path coverage.

`ProgramRuntimeHostIntegrationTest` and `ProgramRuntimeActorIntegrationTest` cover ROM boot, foreground child execution, reboot, terminal
editing, compiler requests, verified installation, Unicode input, and filesystem persistence through the real native
library. The NeoForge GameTests add production registration and ticking, removal/close, observed resource snapshots,
two-computer compilation, persistent programming and world restart, tombstone recovery, IDE filesystem import, and
world-facing redstone.
These are vertical ownership checks, not replacements for the direct Rust tests of the same components.

## Inventory notes

The issue #614 audit recorded 538 registered Rust tests and nine ignored cases at that revision. Those are historical
counts, not the current suite size. To inspect the pinned checkout, run:

```sh
cargo test --manifest-path host/compukter-vm/Cargo.toml --workspace --locked --offline -- --list
```

A test listing establishes inventory, not successful execution. Hardware timing and fixture regeneration remain
explicit opt-in checks; select the current semantic and native integration tasks from [Verification](verification.md).

This inventory makes no line- or branch-coverage percentage claim.
The matrix is derived from registered test targets, test names, owning source modules, Gradle task graphs, and the
observable boundary each test exercises.

## Focused gaps

The completed issue #39 audit describes its recorded scope; it does not establish coverage of later features.
New runtime surfaces must extend this matrix and add evidence at their owning Rust, portable Kotlin, FFM/JNI and
vertical boundaries as applicable. Document the limits of each check rather than treating aggregate success as
proof of an untested boundary.
