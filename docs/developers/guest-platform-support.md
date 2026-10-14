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
| `stdlib:core` | Core helpers such as `require`, supported array construction, inline scope functions, indexed `repeat`, ranges, collections and reusable vector/rectangle geometry |
| `compukter:core` | Runtime and environment APIs: terminal and `kotlin.io`, filesystem, compiler, child processes, cooperative `Task` / `Tasks`, redstone, sound and persistent RGB displays |

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

One host request can return bounded immutable data/value records with non-null scalar or nested-record fields.
The SDK checks constructor-safe declarations and canonical stdlib geometry, generates typed host mirrors and retains the nominal field shape in the
addon ABI. Native admission validates the destination layout; resume copies the response, and normal budgeted
advancement materializes rooted nodes without Guest constructors. Arrays, nullable fields, record arguments
and cyclic records are unsupported. Ordinary data replies require Runtime ABI 1.13; value replies require 1.20.
Inline value fields avoid Guest record allocations, with staged references rooted across GC and checkpoint restoration.

**Evidence:** `ArtifactValidatorTest`, test `structured host responses require ABI 1 13 while String responses
remain compatible`, `MinimalScriptLoweringTest` test `addon value responses preserve direct nested and managed layouts`
and `testKotlinAddonValueResponsesVmConformance`,
[`session_tests.rs`](https://github.com/CertifiedBadIdeas/Compukter-VM/blob/main/src/execution/session_tests.rs),
tests `inline_records_copy_nested_values_without_snapshot_or_vector_allocations`,
`checkpoint_resumes_inline_record_components_and_staged_references`,
`records_copy_validate_and_publish_only_after_budgeted_materialization`,
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

### Cosmonautics space observations on Minecraft 1.21.1

The independent Cosmonautics addon exposes `cosmonautics.space.Space.status()`, `planet()`, `orbit()` and
`guidance()`. The latter copies dimension/frame/instance/tick identity with unit prograde, radial-out and orbit-normal
vectors from one Deep Space instance observation. Missing or numerically degenerate state throws
`IllegalStateException`. Separate Sable pose observations are not atomic with it. The addon README owns units,
timing and the pinned upstream world-axis convention; instance orbital motion differs from construction solver motion.

**Evidence:** addon `GuidanceDirectionsTest` and `OrbitObservationTest`, actual-bundle compilation through
`:compiler-k2:compileSpaceTelemetryExample` and `:compiler-k2:compileOrbitalProgradeExample`, and
`testKotlinOrbitalPointingVmConformance` for the shipped example's pure feedback logic. Real world telemetry and
pointing convergence remain a separate manual scenario; no launch or orbital-insertion support is claimed.
See [Cosmonautics addon](https://github.com/CertifiedBadIdeas/Compukters-cosmonautics/blob/main/README.md)
and the workspace `examples/orbital-prograde/README.md`.

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
conflicts. See [Create addon]({{ '/CREATE/' | relative_url }}) for setup and [Create
kinetics]({{ '/CREATE-KINETICS/' | relative_url }}) for the API.

### Create Stock Ticker on Minecraft 1.21.1

**Status:** Supported.

The optional `create` addon exposes adjacent or named Stock Tickers through `Logistics`. Programs can capture
bounded stock snapshots, find entries by exact item ID, inspect each variant's display name and count, and
request bounded packaging to a validated address. A request reports Create's acceptance, not delivery.
Snapshots must be closed after use and stale device handles fail.

**Evidence:** `StockTickerHostStateTest`, the independent Create addon `check`, and its packaged Guest API
bundle. See [Create logistics]({{ '/CREATE-LOGISTICS/' | relative_url }}).

### Create steam boiler on Minecraft 1.21.1

**Status:** Supported.

The optional `create` addon exposes adjacent or named active boilers through `Boilers`. Programs read water
supply in mB/t, its 0–18 water level, active heat, effective boiler level, and passive-heating status. Handles
stay bound to the selected Fluid Tank segment and controller.

**Evidence:** `BoilerHostStateTest`, the independent Create addon `check`, and its packaged Guest API bundle.
`CreateBoilerGameTests.namedBoilerLifecycle` executes a Guest program through a real non-controller tank
segment, with water supplied through Create's fluid capability, passive heat, engine/tank changes and cable
reconnection. See [Create boilers]({{ '/CREATE-BOILERS/' | relative_url }}).

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
[`ComputerSoundGameTestScenario`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/minecraft/shared/neoforge/src/gameTest/kotlin/ru/lazyhat/compukters/impl/computer/ComputerSoundGameTestScenario.kt).

### In-world display

**Status:** Supported.

`GraphicalDisplay` addresses a persistent RGB canvas assembled from one or more coplanar panels, including holes.
The program selects 16, 32, 64 or 128 pixels per block. Drawing primitives publish immediately; the inline `frame`
extension supports private, exception-safe grouped updates and hibernation. One program holds the writer lease;
release never erases the published pixels. `TextDisplay` retains its character-grid API, with 20 columns per block
across and 10 rows per block down throughout the composite canvas, rasterized at `HIGH` density. Both are typed peripheral providers, and multiple reachable panels discover as one canvas.

**Evidence:** `DisplayCanvasTest`, `DisplayDirectoryTest`, `DisplayClientImageTest`, `DisplayHostStateTest`,
`DisplayPersistenceTest`, `MinimalScriptLoweringTest`, and the real `GraphicalDisplayGameTestScenario`,
`GraphicalDisplayHibernationGameTestScenario` and `TextDisplayGameTestScenario`. `DisplayHostStateTest`, tests
`legacy text reaches the whole composite grid and crosses panel boundaries` and `legacy text keeps single panel bounds`,
cover composite coordinates, row overflow rejection, cell replacement and unchanged single-panel limits.
See [Display]({{ '/DISPLAY/' | relative_url }}).

### Remote Redstone Port

`RedstonePort` is a typed built-in provider exposing six `RedstonePortSide` values with input reads, weak/direct
outputs, edge/exact/threshold waits and `close()`. Port-level `set` defaults to `RedstonePort.Side.ALL` and
updates all faces atomically; a specific enum side selects one output. Per-face facades expose only input readings and waits. All operations suspend for bounded server host I/O.
The additive `compukters:redstone-port` capability is ABI 1.0. Acquisition is process-exclusive through the shared
peripheral registry; output levels and waits are transient. The computer's own GPIO remains a separate API.

Evidence is provided by `RedstonePortHostTest`, `RedstonePortStateTest`, `PeripheralOwnershipTest`, the deterministic
`redstone port program lowers deterministically for GameTest` fixture and `RedstonePortGameTestScenario`.
See [Redstone]({{ '/REDSTONE/' | relative_url }}) and the `examples/redstone-port` project.

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
[`ComputerRedstoneGameTestScenario`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/minecraft/shared/neoforge/src/gameTest/kotlin/ru/lazyhat/compukters/impl/computer/ComputerRedstoneGameTestScenario.kt).

### Typed terminal events and drawing

**Status:** Supported.

`compukter:core` 7.2.0 replaces the raw public event protocol with `Terminal.readEvent()`.
It blocks until input arrives and returns an owned `TerminalEvent.Text(text)` or
`TerminalEvent.Key(key, action, modifiers)`. `TerminalKey` and `TerminalKeyAction` are enums;
`TerminalModifiers` exposes `shift`, `control`, `alt` and `superKey` Boolean properties.
The facade releases native event ownership before returning, including on decoding failure.
There is no public `awaitEvent` / `event*` / `finishEvent` sequence.

`Terminal.readEvent(submittedLineLimit)` also admits a distinct
`TerminalEvent.SubmittedLine(text)` from host/IDE complete-line submission. The limit is in UTF-16
units (0..4096); zero rejects submission while a line editor has a draft. Ordinary `readEvent()`
does not opt in. Complete lines are atomic: queued/active input, control characters, malformed UTF-16
and oversized lines reject before changing input. The shell enables reception at an empty prompt,
with a 256-unit limit; submitted commands use normal dispatch and session history. Editors and
other raw-input programs remain protected unless they explicitly opt in.

`Terminal.cursor` is a `Position2i`; `Terminal.size` is a `Size2i`. These reuse the standard library's
integer vectors. `setCursor(position)` and `writeAt(position, text)` accept positions, while
`fill(rectangle)` and `fill(rectangle, character)` accept `Rect2i`; an empty rectangle is a no-op.
`setColors(foreground, background)` accepts `TerminalColor` values from the fixed 16-color palette.
Drawing stays bounded by the native grid. Positional drawing does not move the stream cursor.
`write(text)`, `erasePrevious()`, `clear()` and `setCursorVisible(visible)` remain available.
`write` and `writeAt` accept `CharSequence`, `Char` and `CharArray` (whole buffer or explicit
`startIndex`, exclusive `endIndex`). Sequence output uses `toString()`: Strings retain their existing
value and other sequences provide their string representation. Custom sequences should override
`toString()` to return their text. Char and buffer output create no temporary Guest String.
Ranges check `0 <= startIndex <= endIndex <= buffer.size`; invalid ranges throw
`IndexOutOfBoundsException` before drawing. Native preparation copies only selected UTF-16 units,
with existing host quotas and sliced cost accounting; the backing array can be reused immediately
after the call. Surrogate pairs must appear in one text/range call; an isolated surrogate, including
a single Char, renders U+FFFD. Range writes retain ordinary stream controls or positional clipping.
Built-in `cmatrix` draws Char glyphs and `edit` writes its reusable row buffer directly.

```kotlin
import compukter.terminal.*
import kotlin.math.*

fun main() {
    Terminal.setColors(TerminalColor.WHITE, TerminalColor.BLACK)
    Terminal.fill(Rect2i(Vector2i(0, 0), Terminal.size))
    when (val event = Terminal.readEvent()) {
        is TerminalEvent.Text -> Terminal.writeAt(Vector2i(0, 0), event.text)
        is TerminalEvent.Key -> if (event.key == TerminalKey.ENTER) println("Enter")
        is TerminalEvent.SubmittedLine -> println(event.text)
    }
}
```

Ctrl+C is reserved for foreground interruption. At an idle shell it cancels command input; when a
child is running it unwinds that command's process tree and returns status 130 to its parent.
It is forced cancellation, without Guest signal handlers. Ctrl+T is no longer reserved.
Plain C/U text remains normal input; the client maps Ctrl+C and Ctrl+U as key events.
Terminal UI keeps access to the VM-owned history of the last 512 scrolled rows, including after reopening.
Mouse wheel or Shift+PageUp/PageDown browses history; Shift+Home/End jumps to the oldest/live view.
Drag selects a rectangle of cells. Ctrl+C copies a selection; without a selection it retains foreground
termination. Ctrl+Shift+C always stays local, Escape clears a selection, and Ctrl+V delivers ordinary
bounded text input to the running program. Selection does not modify Guest terminal cells. The shell prints a program's complete failure diagnostic and
repeats its first line after a multiline diagnostic to keep the reason visible beneath the stack trace.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `terminal output preserves scalar sequence and buffer contracts`
(`testKotlinTerminalOutputVmConformance`, including 100 identical cmatrix frames with lower Guest heap/cost),
`typed terminal facade lowers observations events and rejects removed APIs`,
`checked in shell compiles deterministically` and `checked in editor compiles deterministically`;
[`ProgramRuntimeHostIntegrationTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/core/src/nativeIntegrationTest/kotlin/ru/lazyhat/compukters/core/device/runtime/program/integration/ProgramRuntimeHostIntegrationTest.kt),
tests `builtin shell edits scalar boundaries and restores history drafts`,
`builtin shell bounds session history and retains the current draft`,
`ROM boot runs a foreground child and reboot creates a fresh shell`,
`IDE submits a complete command to the builtin shell`, and
`IDE complete lines preserve drafts and reject busy children and queued input`;
native `terminal_observations_read_authoritative_geometry_without_mutating_it`, `ctrl_c_*`,
`submitted_line_event_is_atomic_bounded_and_opt_in` and
`checkpoint_preserves_submitted_line_wait_and_owned_event` tests.

### Filesystem facade

**Status:** Supported within the documented portable API.

`compukter.filesystem.Path` is a pure lexical String-backed value class; the explicitly imported
`compukter.filesystem.toPath` extension converts a String with the same syntax validation and no storage access.
Dot components are retained lexically;
`Path.normalize()` removes them and clamps ascent at root. FileSystem normalizes inputs before native
submission; Rust rejects noncanonical native paths and checks the target mount and permissions independently.
`FileSystem` provides typed metadata and errors, binary I/O, strict UTF-8 text,
immutable read snapshots, sorted directory snapshots, directory operations and atomic replacement drafts.
Transfers are bounded independently of file size. Resources belong to each foreground program and survive
hibernation with their positions and revision guards; terminal program outcomes release them.
Optional separately imported `compukter.io.path` extensions delegate to the same operations.
Eager `FileSystem.readLines` and inline `useLines` expose strict UTF-8 line processing; the latter uses a
scoped single-pass Sequence with bounded per-line storage. `FileReader.lineSequence` leaves lifetime to its
caller. Optional Path extensions delegate to the explicit facade. LF/CRLF/CR, lazy validation, encoded byte
bounds and cleanup on early/exceptional/non-local exits follow the [line-reading contract](filesystem.md#reading-lines).

See [Guest filesystem]({{ '/FILESYSTEM/' | relative_url }}) for limits, cleanup and the 0.6.0 source migration.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `portable filesystem API executes binary text snapshots and atomic drafts`,
`lexical Guest paths need no filesystem capability` (including dot normalization/root clamping, constructor validation, nullable/Any/array/list
and hash-key boundaries), `Path value class rejects data class conveniences and reference equality`,
and `Path storage conveniences require explicit extension imports`, and
[`ProgramRuntimeHostIntegrationTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/core/src/nativeIntegrationTest/kotlin/ru/lazyhat/compukters/core/device/runtime/program/integration/ProgramRuntimeHostIntegrationTest.kt),
tests `lexical Guest paths execute without filesystem access`,
`portable Guest filesystem performs bounded atomic IO and typed failures` and
`builtin editor saves its full Unicode buffer without moving the cursor`.
Native lifecycle, quotas, guarded replacements and checkpoint rejection are exercised in
[`computer_filesystem.rs`](https://github.com/CertifiedBadIdeas/Compukter-VM/blob/main/src/computer_filesystem.rs).

**Related work:** [#734](https://github.com/CertifiedBadIdeas/Compukters/issues/734),
[#780](https://github.com/CertifiedBadIdeas/Compukters/issues/780)

### Process facade

**Status:** Supported.

`Process.run(path: Path, args)` normalizes the lexical path and returns typed exited/failed results.
`Process.exit(code)` terminates explicitly. Process argument encoding and diagnostics are task-owned;
path and encoded arguments share a bounded outbound request.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
test `typed process v2 facade lowers without public capability masks or suspend calls`, and
[`ProgramRuntimeHostIntegrationTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/core/src/nativeIntegrationTest/kotlin/ru/lazyhat/compukters/core/device/runtime/program/integration/ProgramRuntimeHostIntegrationTest.kt),
tests `typed Guest compilation and process paths preserve diagnostics permissions and outputs` and
`ROM boot runs a foreground child and reboot creates a fresh shell`.

**Related work:** [#739](https://github.com/CertifiedBadIdeas/Compukters/issues/739)

### Compiler facade

**Status:** Supported.

`Compiler.compile(source: Path, output: Path)` normalizes both paths and returns `CompilationResult.Success`
with a canonical output Path or `CompilationResult.Failed` with a reason and diagnostics from that call.
The source facade has no separate diagnostics query. Rust owns source snapshots, access checks, revision
conflicts and atomic executable installation. The current Guest facade compiles one `.kt` source;
project compilation remains a separate tooling API. See [examples and failure reasons](filesystem.md#compilation-and-process-launch).

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `typed compiler facade lowers Path results and rejects removed APIs` and `checked in kotlinc compiles deterministically`,
and [`ProgramRuntimeHostIntegrationTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/core/src/nativeIntegrationTest/kotlin/ru/lazyhat/compukters/core/device/runtime/program/integration/ProgramRuntimeHostIntegrationTest.kt),
test `typed Guest compilation and process paths preserve diagnostics permissions and outputs`.
Native paired replies, Unicode bounds and checkpoint restoration are covered in
[`computer.rs`](https://github.com/CertifiedBadIdeas/Compukter-VM/blob/main/src/computer.rs) and
[`checkpoint/computer.rs`](https://github.com/CertifiedBadIdeas/Compukter-VM/blob/main/src/checkpoint/computer.rs).

**Related work:** [#739](https://github.com/CertifiedBadIdeas/Compukters/issues/739)

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


## Computer network messages

`compukter:core` 2.2.0 exposes `compukter.network.Network.connected: Boolean`, `send(ByteArray)` and
`receive(): ByteArray`. Ordinary Guest calls lower to the trusted asynchronous `compukters:network` capability ABI 1.0:
operations 0 (connection status), 1 (send) and 2 (receive). The physical cable selects exactly one peer, so the API has no
addresses. A receive retains its task/request identity while waiting and completes through bounded server-thread polling.
The compiler fixture task `:compiler-k2:generateNetworkConformanceArtifacts` and both Minecraft versions' real
`computer_network` GameTests cover all 256 byte values, empty messages and catchable interruption.

Runtime native ABI 23 transports byte arrays directly through JNI and FFM. The outbound call has an aggregate 4096-byte
array limit and the returned array has the same limit. Buffers are owned snapshots; a sender's later writes cannot alter
queued data. Each computer's inbox is shared by its program scopes and holds up to 16 messages. Queues are volatile;
cable mutation, endpoint replacement/reboot and participating chunk unload retire the connection and discard its data.
Restoring a pending receive completes it with `compukter.io.IOException` rather than rebinding it to a new connection.
See the [player network guide]({{ '/PERIPHERALS/' | relative_url }}) for setup and a two-computer echo example.

## Interactive control panels

`compukter:core` 7.3.0 provides `GraphicalDisplay.pollClick(): DisplayClick?`. Empty-hand right-click the
front of a panel; `click.position` uses whole-screen pixel coordinates. Polling and rendering share one
exclusive program lease. Input is bounded and transient; restarting, resizing or rebuilding a screen does
not replay commands. Shift-click retains normal block interaction.

`DisplayWidgets` renders buttons (returning a hit result), indicators, gauges and charts over `Rect2i`
bounds. Pass one click to the widgets in each update and keep command execution outside the drawing frame.
`DisplayHistory(2..1024)` stores chronological samples with fixed capacity; `Float.NaN` creates chart gaps.
Widgets neither poll devices nor retain events. Gauges and charts require finite ordered ranges and clamp
readings to their visual range.

See the tracked [Create drive dashboard](https://github.com/CertifiedBadIdeas/Compukters/tree/dev/examples/create-scada)
for live RPM, stress, controller commands, bounded history and connection/load alarms on a 3×3 display.

### Crafts & Additions liquid burners

The independent `createaddition` addon provides `createaddition.boiler.LiquidBurner` with the common typed
peripheral queries. `status()` returns one immutable record containing recoverable liquid amount/capacity in mB,
Create heat ordinal and remaining burning ticks. The adapter reads upstream state on the server thread and never
mutates fuel. Exact-device replacement or connectivity loss invalidates the handle. See the
[boiler SCADA project](https://github.com/CertifiedBadIdeas/Compukters/tree/dev/examples/boiler-scada) for battery-assisted
startup, steam handover and fuel recovery through Redstone Port outputs.

**Evidence:** `LiquidBurnerHostTest`, the actual `LiquidBurnerGameTests.namedBurnerLifecycle` server test and
`compileBoilerScadaExample` against both actual addon bundles. API execution and compilation do not establish the
complete physical pump/charging sequence, which additionally requires the documented client scenario.

### Batched drawing frames

`compukter:core` 8.0.0 supplies a scoped `DisplayFrame` receiver:

```kotlin
screen.frame {
    clear(Colors.BLACK)
    line(0, 0, 100, 100, Colors.CYAN)
    ui.draw(this)
}
```

Drawing is recorded into packets of at most 4096 bytes/128 commands, sent in order and published together.
Exceptions discard the pending frame. Nested frames fail, and using the recorder after block exit throws
`IllegalStateException`. Keep it within the calling task. Device state queries and input polling use `screen`;
immediate scalar drawing remains available on that handle. `DisplayWidgets` and `DisplayUi.draw` now accept
`DisplayFrame`; construct/use widgets inside the frame instead of retaining them across updates. `CanvasScope`
uses the same recorder and retains local clipping. Text/image and frame pixel-work limits are unchanged.

**Evidence:** `testKotlinDisplayUiVmConformance` executes the linked Guest recorder, nested-frame rejection,
escaped-recorder rejection and 50 repeated SCADA-style frames plus two full-history frames. `DisplayHostStateTest`
compares batched drawing pixels with scalar drawing, rejects malformed packets, validates task ownership and
prevents failed packets from publishing partial frames.

### Declarative display UI

`compukter:core` supplies a small persistent widget tree. `displayUi(bounds) { ... }` builds it once;
`bounds` is a screen-space `Rect2i` that can be moved/resized explicitly. `row(spacing)`, `column(spacing)`
and `at(position)` determine fixed pixel layout. Leaf sizes are explicit, containers derive their content
extents, and resizing the viewport clips content rather than rebuilding/reflowing it. Retain returned
`UiText`, `UiButton`, `UiIndicator`, `UiGauge`, `UiChart`, `UiSlider` or `UiCanvas` references to update
properties. Construction blocks on `displayUi`, `row`, `column` and `at` have `EXACTLY_ONCE`
contracts, so nested blocks can initialize non-null local `val` references. Optional nullable widget
configuration blocks have no definite-initialization guarantee; assign the returned widget instead.
Deferred input/drawing handlers have no calls-in-place contract. Visual property setters and chart history
updates invalidate their widget. There is no recomposition, polling or background scheduler.

```kotlin
val rotation: UiSlider
val ui = displayUi(Rect2i(12 xy 12, 168 by 160)) {
    column(spacing = 8) {
        canvas(168 by 132) {
            onDraw = {
                clear(Colors.BLACK)
                // Local pixel coordinates, clipped to the canvas and UI viewport.
                line(0, 0, size.x - 1, size.y - 1, Colors.CYAN)
            }
        }
        rotation = slider(168 by 20, 0f, (PI * 2.0).toFloat())
    }
}
// In the program's update loop:
ui.dispatch(screen.pollPointer())
val angle = rotation.value // Non-null reference initialized during construction.
ui.render(screen, Colors.BLACK) // No frame or host calls while unchanged.
```

`render(screen, background)` owns the UI viewport and opens a frame only when pixels need repainting.
It clears the damaged rectangle, then repaints intersecting widgets in their original order, including overlays.
Equal property assignments do not invalidate; chart history changes do. `UiElement.invalidate()` or
`invalidate(localBounds)` requests repaint for state captured by custom drawing; an animation must call it
explicitly. `ui.invalidate()` repaints the whole viewport after external drawing or a mode change. Changing
bounds clears the previous viewport; rendering to a different display paints the entire new viewport. Use one
render target per retained tree. Rendering failures retain damage for retry, and invalidation during drawing
remains pending for the next pass. The caller still owns input polling and scheduling.

`draw()` never invokes input handlers or publishes a frame. `CanvasScope` supplies local `pixel`, `line`,
`fillRect`, `rect`, `text` and `image` drawing, with clipping to nested viewports; it is also available from
`frame.canvas(bounds)` inside `screen.frame { ... }` for low-level drawing. `UiCanvas.onPointer` receives widget-local pointer positions.
User drawing handlers still execute ordinary Guest code; use them only to draw, keeping device commands
in input handlers or in the surrounding update loop.

`GraphicalDisplay.pollPointer()` independently polls the transient `DisplayPointerEvent` stream:
`DOWN`, `DRAG`, `UP`, `CANCEL`. Hold empty-hand RMB and move the crosshair over the world display;
release ends the gesture, leaving the screen cancels it. After cancellation, release and press again.
Shift-use, GUI focus, disconnect, lost heartbeats and display lifecycle changes cancel input. Host validation
uses server-side aim, reach, front face and exact live canvas identity; the client supplies only held state.

`ui.dispatch(event)` hit-tests once on Down, choosing the last painted leaf. A disabled button or passive
leaf blocks controls below it; container gaps pass through. Buttons activate once on Down. Each gesture
captures its target through Drag/Up/Cancel, so dragging outside a slider still clamps its value to the
range. Up/Cancel release capture before calling handlers; exceptions propagate and drop that gesture.
Up to eight gestures can be captured. Gesture zero with Cancel cancels all captures after queue overflow.
Moving/resizing `ui.bounds` or calling `cancelInput()` also clears capture. Layout coordinates are fixed;
each input event uses current enabled state, and explicit redispatch is an explicit second delivery.
`dispatch(DisplayClick?)` remains useful for click-only programs with explicit queue policy.

Queues contain at most 64 events, adjacent moves coalesce, and gestures are never persisted or replayed.
Applications own draining and admission; process a bounded batch per update, or discard stale input when
reconnecting controls as the SCADA example does.

Evidence: `testKotlinDisplayUiVmConformance` executes layout, viewport boundaries, overlays, disabled/passive
blocking, capture, local coordinates, cancellation and callback failures. It also records raster host calls
for nested enabled/disabled buttons and 50 framed drawing passes of a SCADA-shaped layout with telemetry
widgets, called through a separate dashboard function to exercise its library-call depth. Incremental cases
cover fifty idle passes without a frame, equal and off-screen updates, shorter text erasure, chart revision,
custom subregions, slider changes, failure/retry, invalidation during drawing and viewport movement.
`DisplayPointerTest` covers queue
limits/coalescing, independent players, release/re-entry, expiry and clipping. `DisplayHostStateTest` checks
capability revisions, little-endian input and clipped drawing. The graphical display GameTest exercises
DSL drawing and the Guest Down/Drag/Up host bridge. The [cube example](https://github.com/CertifiedBadIdeas/Compukters/tree/dev/examples/rotating-cube)
uses a clipped canvas and rotation slider; the SCADA example uses explicitly updated widgets and click-only dispatch.
