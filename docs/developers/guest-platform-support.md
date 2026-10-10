---
layout: default
title: Guest platform and device API support
section: developers
permalink: /GUEST-PLATFORM-SUPPORT/
---

# Guest platform and device API support

[← Guest Kotlin support]({{ '/KOTLIN-SUPPORT/' | relative_url }})

* On this page
{:toc}

This page owns executable platform and device contracts. Kotlin syntax lives in [Guest Kotlin support]({{ '/KOTLIN-SUPPORT/' | relative_url }}); callable declarations are indexed in the [API reference]({{ '/API/' | relative_url }}).

## Built-in platform and addon admission

The platform internally uses the following modules to build and verify its Guest Kotlin surface. They form one
atomic built-in platform and are not selected individually in `compukter.toml`; there is no ambient Kotlin/JVM
classpath.

| Module | Guest surface |
| --- | --- |
| `kotlin:builtins` | Core language types, arrays, function types, and structural declarations required by K2 |
| `stdlib:core` | Core helpers such as `require`, supported array construction, inline scope functions, indexed `repeat`, ranges and collections |
| `compukter:core` | Runtime and environment APIs: terminal and `kotlin.io`, filesystem, compiler, child processes, cooperative `Task` / `Tasks`, redstone, sound and text displays |

These module owners do not rename Kotlin packages or imports. Environment-dependent `kotlin.io` functions
belong to `compukter:core`; the rest of the supported standard library belongs to `stdlib:core`. Module
identities and the platform content hash cover this ownership. Addons depending on removed split-module IDs
must rebuild against the new owners; old and new platform identities cannot be mixed.

Ordinary functions in these modules are compiled ahead of Guest projects into relocatable platform fragments.
Only declarations explicitly marked as native external bindings lower to host capability operations; a Guest
declaration cannot become one merely by copying its package, name, and signature.

The built-in modules are packaged with the tooling workers. Addons instead register an `AddonGuestApiBundle`
on the server. Its deterministic identity covers metadata, sources, capability schemas, and exact callable
bindings. An attached IDE receives the admitted data from the server. Completion can propose APIs from
available inactive addons and enable their addon IDs; diagnostics, parameter information, navigation,
compilation, and cache invalidation then use the same selected API identity without adding the addon JAR to
either worker's JVM classpath.

**Evidence:**
[`CompletionQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/CompletionQueryTest.kt)
and
[`IdeCompletionPlannerTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/analysis/IdeCompletionPlannerTest.kt).
The server compiler derives full platform-module names and hashes from that same target profile; addon
payloads retain their separate addon IDs.

**Evidence:** `NeoForgeCompilerServicesTest`, test `server advertises an admitted addon bundle with its exact
content identity`, which checks both advertised and configured identities for an addon whose ID differs from
its module name. The IDE compile profile includes built-in modules and the project's selected addons only,
even when the server advertises more addons. Each selected external module has its matching payload.

**Evidence:** `CompileProfileResolverTest`, test `a target with two addons includes only project selected
modules and matching payloads` covers no addon, either single addon and both addons for local and
attached-target compilation.

### Structured asynchronous addon results

**Status:** Supported.

One host request can return a bounded immutable data record with non-null scalar or nested-record fields. The
SDK checks pure data declarations, generates typed host mirrors and retains the nominal field shape in the
addon ABI. Native admission validates the destination layout; resume copies the response, and normal budgeted
advancement materializes rooted nodes without Guest constructors. Arrays, nullable fields, record arguments
and cyclic records are unsupported. Structured programs require Runtime ABI 1.13.

**Evidence:** `ArtifactValidatorTest`, test `structured host responses require ABI 1 13 while String responses
remain compatible`,
[`session_tests.rs`](https://github.com/CertifiedBadIdeas/Compukter-VM/blob/main/src/execution/session_tests.rs),
tests `records_copy_validate_and_publish_only_after_budgeted_materialization`,
`record_admission_requires_runtime_and_exact_nested_layout`,
`record_allocation_failure_is_budgeted_bounded_and_releases_unpublished_storage` and
`other_task_responses_do_not_replace_an_incomplete_record_frame`, plus
[`SableObservationGameTests`](https://github.com/CertifiedBadIdeas/Compukters-sable/blob/main/src/gameTest/kotlin/ru/lazyhat/compukters/integration/sable/SableObservationGameTests.kt),
scenario `computerAssemblyAndReturn`, compiling and executing the typed Guest API through JNI.

### Program-owned addon resources

**Status:** Supported.

Each live Guest program receives its own addon hosts on first use. Completing a child closes its devices while
a suspended parent retains its hosts. Computer shutdown closes all scopes, and late responses cannot bind to
another program. Native transports require C ABI 20; Guest artifact ABI is unchanged.

**Evidence:** `ScopedProgramAddonHostTest`, `ProgramRuntimeHostTest`, `ProgramRuntimeActorProcessorTest` and
native `computer.rs` process lifetime/request routing tests.

### Creative Thruster control

**Status:** Supported.

The independent Minecraft 1.21.1 Propulsion addon exposes `propulsion.thrusters.CreativeThruster.named(name)`,
normalized Double throttle, saved thrust percentage, `close()` and immutable `CreativeThrusterState` snapshots
in kN. One program owns writes; reading does not claim control. Completion, loss of reachability, removal and Sable
assembly release digital input. Upstream Propulsion retains Float precision and its ordinary startup,
atmosphere and obstruction behavior.

**Evidence:**
[`CreativeThrusterGameTests`](https://github.com/CertifiedBadIdeas/Compukers-propulsion/blob/main/src/gameTest/kotlin/ru/lazyhat/compukters/integration/propulsion/CreativeThrusterGameTests.kt),
`guestControlLifetime` and `multiblockAssemblyClearsControl` compile and run real Guest programs through JNI.
See the [addon README](https://github.com/CertifiedBadIdeas/Compukers-propulsion/blob/main/README.md).

### Creative Vector Thruster control

**Status:** Supported.

`CreativeVectorThruster.named(name)` exposes a separate typed handle and `CreativeVectorThrusterState`,
normalized throttle, local X/Y steering and absolute creative thrust in kN. Commands claim one program owner;
close/completion/disconnection/removal return throttle, steering and thrust to ordinary redstone and saved
configuration. Steering retains Float precision, 1/15 steps and ordinary tick smoothing; no extra physics or
VM ticks. Live redstone-link inputs continue updating while owned. Full saves and Sable copies discard program
commands while client packets retain them for rendering.

**Evidence:** `CreativeVectorThrusterGameTests.guestVectorControlLifetime` and `vectorAssemblyClearsControl`
compile and execute real Guest programs through JNI on actual upstream blocks.

## Compukters Guest APIs

### Typed peripheral providers

**Status:** Supported.

`Peripheral` identifies device wrappers; companion values inheriting `TypedPeripheralProvider<T>` share strict
and optional first selection, typed predicates, filtering, all devices, relative side and named acquisition.
The base owns bounded snapshots and exact-instance handles while addons retain device operations. Core
displays, all five Create devices and both Propulsion creative engine types use this contract.

**Evidence:** `PeripheralSessionTest`, `PeripheralProgramHostTest`,
`testKotlinPeripheralQueriesVmConformance`, `TextDisplayGameTestScenario`, and the addon lifecycle GameTests.
Source and external addon metadata both supply the IDE provider role; expression completion has a `P` badge
and separate color while type references remain ordinary classes.

**Evidence:** `SemanticTokenQueryTest`, `CompletionQueryTest`, `AnalysisProtocolRoundTripTest` and
`IdeRendererStateTest`.

### Create kinetics on Minecraft 1.21.1

**Status:** Supported.

When Create 6.0.10 through 6.0.x is loaded, the optional `create` addon exposes computer-local sides and
persistent names reachable through peripheral networks through `Kinetics`. Programs can read exact `Float`
speed, stress, and capacity values; wait for speed or load changes; and read or set a rotation controller's
target speed. Handles remain bound to the exact acquired block entity and fail rather than rebinding after
replacement.

**Evidence:** `KineticsHostStateTest`, including `named acquisition routes every kinetic type and shares
handles with side acquisition`; `ComputerPeripheralLookupTest`; and neutral addon IDE
diagnostic/completion/parameter-information tests. The standalone addon GameTest
`CreatePeripheralGameTests.namedKineticLifecycle` compiles and executes a Guest program against motor-driven
real gauges and a rotation controller, including cable cuts, reconnection, block replacement and configurator
conflicts. See [Create addon](https://certifiedbadideas.github.io/Compukters/CREATE/) for setup and [Create
kinetics](https://certifiedbadideas.github.io/Compukters/CREATE-KINETICS/) for the API.

### Create Stock Ticker on Minecraft 1.21.1

**Status:** Supported.

The optional `create` addon exposes adjacent or named Stock Tickers through `Logistics`. Programs can capture
bounded stock snapshots, find entries by exact item ID, inspect each variant's display name and count, and
request bounded packaging to a validated address. A request reports Create's acceptance, not delivery.
Snapshots must be closed after use and stale device handles fail.

**Evidence:** `StockTickerHostStateTest`, the independent Create addon `check`, and its packaged Guest API
bundle. See [Create logistics](https://certifiedbadideas.github.io/Compukters/CREATE-LOGISTICS/).

### Create steam boiler on Minecraft 1.21.1

**Status:** Supported.

The optional `create` addon exposes adjacent or named active boilers through `Boilers`. Programs read water
supply in mB/t, its 0–18 water level, active heat, effective boiler level, and passive-heating status. Handles
stay bound to the selected Fluid Tank segment and controller.

**Evidence:** `BoilerHostStateTest`, the independent Create addon `check`, and its packaged Guest API bundle.
`CreateBoilerGameTests.namedBoilerLifecycle` executes a Guest program through a real non-controller tank
segment, with water supplied through Create's fluid capability, passive heat, engine/tank changes and cable
reconnection. See [Create boilers](https://certifiedbadideas.github.io/Compukters/CREATE-BOILERS/).

### Addon Guest API bundles

**Status:** Supported.

A loader integration can register one bounded, versioned Kotlin metadata/source bundle under its addon ID,
with exact capability schemas and intrinsic bindings kept internal. The server is the authority for
availability; compiler and IDE workers accept only the exact advertised bytes and content hash, reject
malformed or shadowing bundles, and never execute addon JVM code. The first producer is the `create`
integration, whose declarations and contract are owned by the Create addon and built against the canonical
base platform.

### One-shot sound

**Status:** Supported.

`Sound.beep(note, volume = 100)` emits the vanilla note-block pling from the computer and return whether the
server admitted it. Notes are bounded to `0..24`, with `12` as neutral pitch; volume is bounded to `1..100`
and defaults to `100`. A computer may emit once every four ticks, the server admits at most 64 computer sounds
per tick, and rejected sounds are not queued. The VM validates the scalar request, while the actor carrier
performs the Minecraft call on the server thread before resuming the Guest Boolean.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
test `sound beep lowers deterministically to a blocking Boolean capability operation`,
[`computer.rs`](https://github.com/CertifiedBadIdeas/Compukter-VM/blob/main/src/computer.rs), sound request
tests, and
[`ComputerSoundGameTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/minecraft/v26_1/v26_1-neoforge/src/gameTest/kotlin/ru/lazyhat/compukters/impl/computer/ComputerSoundGameTest.kt).

### In-world text display

**Status:** Supported.

`TextDisplay.named(name)` or `TextDisplay.at(Side.front)` acquires an exact display block. Programs write and
clear its independent 20x10 grid. One computer holds the active output lease; the screen clears when that
computer stops or disconnects. Bounds and text are validated on the server.

**Evidence:** `MinimalScriptLoweringTest`, `DisplayBufferTest`, `DisplayHostStateTest`, and the real
`TextDisplayGameTestScenario`. See [Text display](https://certifiedbadideas.github.io/Compukters/DISPLAY/).

### Redstone GPIO

**Status:** Supported.

`Redstone.<side>` exposes immediate `get()`, edge-triggered `await()`, exact `await(level)`, threshold
`awaitAtLeast(level)`, and blocking `set(level, power = Redstone.Power.WEAK)`, with `Redstone.Power.DIRECT`
for direct power. These operations lower through the trusted scalar capability while packed output batching
remains private to the runtime. Rust waiter tests, core batch-commit tests, and the real NeoForge
`compukters:computer_redstone` GameTest cover the complete path.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
test `redstone program lowers deterministically for vm conformance`,
[`computer.rs`](https://github.com/CertifiedBadIdeas/Compukter-VM/blob/main/src/computer.rs), redstone tests,
and
[`ComputerRedstoneGameTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/minecraft/v26_1/v26_1-neoforge/src/gameTest/kotlin/ru/lazyhat/compukters/impl/computer/ComputerRedstoneGameTest.kt).

### Terminal write, event wait, and key result

**Status:** Supported.

`Terminal.write`, `Terminal.awaitEvent`, and `Terminal.eventKey` lower to exact terminal capability calls and
execute across a host request.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
test `ordinary project call resumes transparently across host blocking`, and
[`kotlin_writer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs),
test `k2_ordinary_project_call_resumes_across_async_capability`.

### Remaining raw terminal operations

**Status:** Partial.

Clear, erase, text and action/modifier event fields, and event completion lower through trusted signatures and
have device-level VM tests, but lack generated Kotlin-to-VM execution coverage.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
test `shell language subset lowers control flow scalars strings and raw terminal calls`, paired with
[`terminal_device.rs`](https://github.com/CertifiedBadIdeas/Compukter-VM/blob/main/tests/terminal_device.rs),
tests `stable_key_and_atomic_text_events_merge_in_fifo_order` and
`input_limits_reject_whole_events_without_partial_queue_mutation`.

**Related work:** not scheduled

### Positional terminal drawing

**Status:** Partial.

Cursor position and visibility, palette colors, `writeAt`, and rectangular `fill` lower through exact trusted
signatures and have VM device conformance, but no generated Kotlin program executes the complete facade end to
end.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
test `positional terminal facade lowers through exact trusted signatures`, paired with
[`terminal_device.rs`](https://github.com/CertifiedBadIdeas/Compukter-VM/blob/main/tests/terminal_device.rs),
tests `positional_patch_and_fill_do_not_move_the_stream_cursor` and
`positional_terminal_write_clips_one_row_and_decodes_scalars`.

**Related work:** not scheduled

### Filesystem facade

**Status:** Partial.

`stat`, `list`, `readText`, and `writeText` have exact trusted signatures and bounded VM operations. Lowering
coverage currently executes only at the compiler/VM sides separately.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
test `filesystem text facade lowers through exact trusted signatures`, and
[`computer.rs`](https://github.com/CertifiedBadIdeas/Compukter-VM/blob/main/src/computer.rs), tests
`filesystem_text_response_is_bounded_before_guest_materialization` and
`filesystem_text_write_replaces_existing_bytes_through_the_machine`.

**Related work:** not scheduled

### Process facade

**Status:** Partial.

`Process.run(path, args)` returns typed exited/failed results, and `Process.exit(code)` terminates explicitly.
The source facade and VM process contract are covered separately rather than by one end-to-end generated
program.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
test `typed process v2 facade lowers without public capability masks or suspend calls`, paired with
[`computer.rs`](https://github.com/CertifiedBadIdeas/Compukter-VM/blob/main/src/computer.rs), tests
`process_v2_run_materializes_structured_arguments_for_the_child` and
`process_v2_explicit_exit_preserves_all_codes_and_rejects_invalid_values`.

**Related work:** not scheduled

### Compiler facade

**Status:** Partial.

`Compiler.compile(source, output)` and `Compiler.diagnostics()` are published by `compukter:core`, and the
checked-in `/rom/kotlinc` program compiles deterministically. Full Guest-to-host compilation behavior is
tested at the VM transaction layer rather than as one generated Kotlin execution test.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
test `checked in kotlinc compiles deterministically`, paired with
[`computer.rs`](https://github.com/CertifiedBadIdeas/Compukter-VM/blob/main/src/computer.rs), test
`compiler_transaction_snapshots_and_atomically_installs_an_executable`.

**Related work:** not scheduled

### Trusted API identity

**Status:** Supported.

A user declaration cannot impersonate a Guest intrinsic merely by copying its name and signature. The
canonical registry keys every external binding by selected platform module, Kotlin callable ID, and exact
canonical signature; lowering also verifies that the declaration came from native platform metadata or the
exact platform source module.

**Evidence:**
[`TrustedIntrinsicContractTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2-engine/src/test/kotlin/ru/lazyhat/compukters/compiler/k2/engine/intrinsic/TrustedIntrinsicContractTest.kt)
and
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
test `platform callable lookalike remains an ordinary project call`.
