---
layout: default
title: Changelog
description: User-visible changes in every Compukters release.
permalink: /CHANGELOG/
section: players
---

# Changelog

This page records user-visible Compukters changes. The newest version is first; releases remain as permanent section
headings so this page has one stable URL that can be shared outside the repository.

## 0.5.0 — In development

This release expands Guest Kotlin, adds independently installed Create, Sable and Propulsion integrations for
Minecraft 1.21.1, and improves the in-game IDE and computer runtime. Exact language and library boundaries are
listed in [Kotlin support](developers/kotlin-support.md) and [standard library support](developers/stdlib-support.md).

### Release delivery

- Autonomous mod archives use Runtime 0.21.3 / native ABI 21, including checkpoint transport and compact
  debug-path and safepoint-root readers, with exact published bundle and VM revision checks.
- Native compatibility depends on ABI, permitting any `0.21.x` Runtime independently of the development
  VM source revision. Release selection and checksums remain explicit; mod archives and inventory record
  the actual selected native component.
- Successful Runtime publication prepares the next development revision on `main`. Version preparation uses
  the latest complete published release; incompatible changes explicitly raise the version and native ABI
  together once, and further bump requests keep that candidate until publication.
- Runtime revisions sharing one native ABI retain checkpoint compatibility in both directions. Checkpoint
  identity uses the numeric ABI and logical schema; CI checks restoration between builds of adjacent revisions.
- Runtime CI runs its Rust checks, native FFI/JNI builds and smoke tests, bundle packaging and inspection on both
  Linux and Windows in one workflow for ordinary pushes, pull requests and release tags. It verifies the complete
  bundle set and checksums before publication; manual runs validate without publishing.
- Runtime workflow runs for one commit reuse its verified Linux/Windows archives across branch and tag pushes,
  checking the exact source identity and original checksums before publication. Missing or expired archives
  trigger a complete build; queued runs retain the release publication.
- `collectDistributionJars` builds Rust locally; `collectReleaseDistributionJars` downloads pinned Linux/Windows
  Runtime bundles and applies both tagged release gates. Both verify the mod and first-party addons, collecting
  archives in `dist/` by Minecraft version and removing stale files on subsequent runs.
- Verified autonomous NeoForge releases can be published to GitHub Releases and Modrinth from the same tagged
  artifacts, with component identities, checksums and resumable publication. GitHub Releases also includes the
  Create, Sable and Propulsion addon archives. Manual workflow runs validate the
  candidate without publishing.

### Guest Kotlin

- Guest `HashMap` and `HashSet` support typed and nullable keys/elements, lookup, mutation, growth and
  iteration under ordinary VM heap and instruction budgets. Maps expose live key/value/entry views;
  value-class keys retain nominal value equality, including multi-field layouts. `Pair`/`to`, direct
  `mapOf`/`mutableMapOf`/`setOf`/`mutableSetOf` factories and inline `getOrPut` simplify construction and
  defaults; a runnable inventory-report example demonstrates stock totals and thresholds.
  Structural collection equality and type-argument widening remain outside this subset.

- Compilation reuses prepared libraries for repeated module selections, avoids repeated module hashing during
  linking, and allocates less temporary memory when encoding executable artifacts and library identities. Semantic
  hashing checks debug metadata without encoding it.
- All twelve primitive types are supported: `Boolean`, `Char`, `Byte`, `Short`, `Int`, `Long`, `Float`, `Double`,
  `UByte`, `UShort`, `UInt` and `ULong`. Applicable arithmetic, comparisons, conversions, increment/decrement,
  bit operations, constants, text and console output retain their source semantics. Signed narrow bit operations
  use `kotlin.experimental`; unsigned operations preserve the full numeric range. Nullable primitives retain
  distinct nominal boxes through `Any?` and checked casts.
- All twelve primitive array families support size and initializer constructors, factories, indexed access,
  direct `for` iteration, resizing and overlapping `copyInto`. Byte/short arrays use compact storage. Supported
  reference arrays provide factories, indexed access, nullable slots and bulk copies; aliases and array-to-`Any`
  conversions preserve identity. Character arrays and strings preserve exact UTF-16 code units.
- Integral and Char ranges/progressions support stored bounds, membership, iterators, `until`, `..<`, `downTo`
  and positive steps, with safe termination at numeric limits. Direct Int loops retain allocation-free lowering.
  Floating ranges use IEEE membership; Boolean ranges use comparable ordering.
- Value classes support one or multiple immutable primitive, reference or nested fields and concrete generic
  substitutions. Methods, interface bridges, destructuring, typed callbacks and method/constructor references
  preserve their layouts. Constructor references execute initializers and checks. Direct locals and calls use
  compact frames; nullable values, `Any`, interfaces, fields, collections and closure/task captures use nominal
  managed boxes. Equality, hashing and text preserve structural value semantics, including NaN and signed zero.
  Precompiled addon libraries and the IDE share the same nominal types and members.
- Supported classes and interfaces provide instance methods, overrides, virtual/interface dispatch, mutable
  properties, computed accessors, abstract properties and interface defaults, including `super<Interface>` calls.
  Statically known methods declared in final classes use direct calls, reducing dispatch overhead while calls
  through parent classes and interfaces retain runtime selection.
  Primary constructors evaluate explicit arguments and defaults in order, then class properties and `init` blocks.
  Sealed interfaces, supported data classes and stateless enums participate in type branches and smart casts;
  exhaustive `when` can return references without an explicit `else`.
- Generic functions and supported generic classes/interfaces specialize concrete uses without erasing scalar
  fields or calls. Named objects and companions retain a shared managed instance and can inherit specialized
  abstract provider classes or generic interface defaults with typed predicates.
- Supported function values can be passed, returned, stored and invoked without a JVM arity cutoff. Lambdas retain
  immutable captures and share mutable capture cells across nested closures; captured variables that are never
  reassigned avoid the extra cell. Top-level, bound/unbound method and constructor references retain dispatch and
  supported constructor defaults. Top-level and extension `inline` functions support concrete generic callbacks
  and non-local returns within bounded compiler expansion limits.
- Supported nullable references and values provide null comparisons, Elvis and safe calls. Nullable value-class
  assertions and safe casts recover their concrete layouts. Operations waiting for the world, a task or a channel
  suspend ordinary functions transparently; Guest source does not use Kotlin `suspend`. `Tasks.sleepTicks(n)`
  waits for server ticks while other runnable tasks continue without spending the sleeping task's instruction budget.
- `throw`, `try`, typed `catch`, rethrow and `finally` preserve exception identity across calls and task suspension.
  User subclasses retain nullable messages and causes. Preconditions use catchable exceptions with lazy messages;
  arithmetic, bounds, null/cast and host I/O failures are catchable. Cancellation, quotas and VM faults remain terminal.
- `equals`, `==`, `!=`, `hashCode`, `toString`, interpolation and concatenation honor supported value semantics
  and virtual overrides across compiled libraries. Data-class constructor properties participate in generated
  equality/hashing; array equality remains identity-based. Reference identity comparisons preserve aliases,
  and default object text/hash use stable live VM identity.

### Standard library

- `kotlin.math` provides portable Float/Double mathematics: trigonometry, roots, logarithms, powers,
  rounding and adjacent IEEE values, plus `PI`, `E` and Int/Long helpers. NaN, infinity and signed zero
  retain defined semantics; integer rounding saturates and raises a catchable exception for NaN.

- `List`, `MutableList` and `ArrayList` support all twelve primitive element types, nullable elements, references
  and value classes. Non-null primitive storage remains unboxed; nullable and universal views use nominal boxes
  without copying the list. Floating collection equality equates NaNs and distinguishes signed zeros.
- Lists support factories, indexed reads, growth, insertion, replacement, removal, clearing and mutable iterators.
  Read-only views share the underlying object. Structural mutations invalidate iterators; indexed replacement does not.
  Searches, membership and `any`/`all`/`none` work on supported iterables, including user-defined implementations.
- Selection includes `first`, `firstOrNull`, `lastOrNull`, `find` and `getOrNull`, with the documented predicate
  overloads. Strict `first` throws `NoSuchElementException` only when selection is absent, preserving a selected
  null element. `fold` specializes element and accumulator types independently.
- `map`, `filter`, `mapNotNull` and `filterNotNull` preserve traversal order and supported values. `map` on a
  statically typed collection reserves its known size; `mapNotNull` avoids an intermediate list. `mapTo`,
  `filterTo` and `mapNotNullTo` append to reusable destinations and return the same collection.
- Scope functions `let`, `run`, `with`, `apply`, `also`, `takeIf` and `takeUnless`, iterable `forEach` /
  `forEachIndexed`, and indexed `repeat` support inline callbacks and non-local returns from direct lambdas.
- Strings provide UTF-16 ordering, search, Char/backward search, blank checks, Unicode-whitespace trimming,
  delimiter extraction with fallback, prefix/suffix removal, limited Char/String splitting, CRLF/LF/CR line splitting,
  and case-sensitive replacement. Splitting retains empty parts; replacement checks output-length overflow.
  `String.toIntOrNull()` parses optional-sign decimal input and returns null for invalid or overflowing values.
- Guest libraries combine precompiled ordinary implementations with generic/inline source bodies while preserving
  visibility. Programs and dependent libraries reuse canonical concrete specializations, including those appearing
  in addon dependency fragments; conflicting owners are rejected. Helpers, ranges and collections belong to
  `stdlib:core`; computer environment APIs belong to `compukter:core`, with Kotlin packages and imports retained.

<a id="addons-create-and-sable"></a>

### Addons and peripherals

- Independent addon mods expose typed Guest APIs through a Gradle SDK that generates ABI locks, host contracts,
  capability schemas, bindings and Guest bundles. The host API and thin NeoForge adapters are compile-only;
  workers consume admitted metadata/source data without loading addon implementation code. Projects select addon
  IDs in `compukter.toml`; `compukter.lock` retains exact versions and hashes. IDE compilation activates only the
  selected addons and their matching bundles.
- First-party addons use independent `x.y` versions: `x` identifies the API compatibility line and `y` a compatible
  update. Production JAR names also carry the target Compukters major/minor line; loader metadata restricts the
  base mod to the build's minimum version within that line. NeoForge sees the base product version independently
  of Minecraft/loader archive prefixes, with snapshot suffixes retained in development builds.
- Passive peripheral cables support orthogonal, branching and looping connections across loaded chunks without
  loading additional chunks. Direct contacts on all six computer faces and cable contacts share logical identity,
  deduplication and ambiguous-name handling. The Peripheral Configurator edits persistent world-owned device names,
  reports duplicates, inspects cable networks and rejects stale changes; Shift-use clears a name.
- Text displays, Create devices and Propulsion creative engines use typed companion providers with `first`,
  `firstOrNull`, `filter`, `all`, `at`/`atOrNull` and `named`/`namedOrNull`. Handles remain bound to exact devices;
  replacement or loss of reachability cannot silently redirect them. Addons register typed discovery contracts
  while retaining ownership of their device operations.
- The base text display provides an independent 20x10 grid with an exclusive writer. Programs acquire it by side,
  typed discovery or name, write text and clear it; the screen clears when its writer stops or disconnects.
- The optional Create addon supports Create 6.0.x on Minecraft 1.21.1. Speedometers and stressometers report exact
  Float values and wait for changes; rotation controllers set speed. Stock Tickers expose bounded stock snapshots,
  exact item variants and packaging requests whose acceptance does not imply delivery. Active steam boilers are
  discoverable through tank segments and report water supply, gauge level, heat, effective level and passive heating.
- The optional Sable addon provides `sable.physics.Physics.snapshot()` for construction-mounted computers,
  returning typed identity, dimension, world tick, paused state, logical pose and solver velocities as one bounded
  observation. Unavailable constructions throw `IllegalStateException`; observations retain ordinary VM cadence.
- The optional Propulsion addon controls Creative Thrusters and Creative Vector Thrusters: normalized Double
  throttle, local steering, absolute vector thrust in kN, ordinary-engine thrust percentage, typed snapshots and
  computer-relative construction-local mount geometry. Fractional steering retains upstream Float precision
  and normal nozzle smoothing. One program owns writes; close, completion, disconnection or removal releases control
  and restores current redstone/link inputs. Saves and construction copies retain engine configuration without
  transferring program commands or leaving a residual throttle/steering envelope.
- A runnable `pid-one-thruster` example combines Sable pose observations and Propulsion vector steering to
  hold a world point with a lower upward-facing engine, using position PID and pitch/roll damping with configurable gains.
- Addon resources belong to individual programs: an exiting child releases its hosts while suspended parents retain
  theirs. Computer shutdown closes every scope and rejects late responses. Generated handlers support Long,
  Double, Char and bounded immutable nested data-record results, preserving IEEE bits and UTF-16 code units;
  the VM validates and materializes records under existing execution and allocation budgets.

### In-game IDE

- The compiler worker stays warm for two minutes after closing the IDE, reducing repeated compilation delays when
  editing and testing programs between IDE visits. Closing an active build still cancels it; idle timeout and game
  shutdown release the worker.

- Local projects support HTTPS Git clone, opening existing directories in place, status/diff,
  selected-file commits, history, branches, fetch, fast-forward-only pull and push. Tokens are masked and session-only; merge and
  SSH are deferred. Git operations save modified buffers first and refresh working files and analysis afterward.
  Git metadata stays outside compiler/deployment inputs; folder renames update open descendant buffers. Changes uses
  file checkboxes, an editable commit message and author fields remembered across IDE and game restarts, with a colored
  HEAD-to-working preview beside the list or on its own tab. Branches and repository/account actions use menus. A left icon stripe opens tool windows, with
  Git Log at the bottom; history opens below the editor and switches with Problems. All IDE text uses JetBrains Mono. Unrelated staged
  changes remain preserved. Commit & Push pushes only after a successful commit, and failed commits keep their drafts.
  Git status colors cover file names and folders; source gutters mark added, modified and deleted lines against
  HEAD, including unsaved edits, through read-only background inspection.
  Project-tree highlights align with their clickable rows; Commit checkboxes render independently of font glyphs.
- Text-entry dialogs show a labeled input field with a contrasting background, focus border and visible caret.
  Long values scroll to keep the end of the input visible; authentication tokens remain masked.
- File switches preserve caret, viewport and undo history in a bounded document cache. The editor highlights the
  current line and uses contrasting panels, draggable dividers and popup shadows. The IDE uses a fixed scale of 3
  at every window size, independently of the Minecraft GUI scale.
- Kotlin lexical highlighting colors the `value` modifier, function calls, qualified/use-site annotations and Unicode
  escapes; numeric literals remain separate from range operators and member access while semantic colors take precedence.
- `Alt+F7` opens semantic Find Usages with navigable context. Clickable problems, gutter markers, status counts
  and `F2` / `Shift+F2` navigation use matching source text and reject stale locations. Method declarations show
  non-zero project usage counts that distinguish overloads and remain stable during unrelated edits.
- `Shift+F6` renames supported project symbols across files and unsaved buffers after K2 checks collisions and
  bindings. The change is undoable across affected files; library symbols, `main` and unsupported convention or
  inheritance declarations remain outside this action.
- Caret placement highlights resolved declarations/references, including interpolation; typing hides semantic
  occurrence marks until the caret is explicitly moved. Text selection highlights literal occurrences, while
  `Ctrl+F` provides case-sensitive search, a counter and Enter/Shift+Enter navigation in writable or read-only files.
- `Ctrl+/` toggles Kotlin/TOML line comments while preserving indentation, selection and line endings in one
  undoable edit. Enter inside an empty lambda expands an indented body and moves the closing brace.
- Completion supports built-in numeric members, receiver-specialized signatures, package context and aligned
  return types, including safe-call and literal receivers. Call insertion reuses delimiters, chooses parentheses
  or a trailing lambda, and places the caret at the first required input; imports and caret placement undo together.
- Identifier matching supports case-insensitive word fragments and CamelCase with prefix ranking and highlighted
  matches. Interpolation suggests visible variables after `$`. Hover shows bounded KDoc from project and attached
  library declarations. Completion temporarily pauses diagnostic passes while retaining unrelated problems.
- Peripheral provider values use a dedicated color and `P` completion badge; device type references retain class
  presentation. Installed-addon APIs participate in completion, imports, parameter information and navigation;
  choosing an inactive addon API enables that addon. A reused addon-origin index avoids decoding bundles for each suggestion.
- The editor, completion, hover, terminals and text displays use bundled JetBrains Mono without ligatures.
  Editor line spacing is 1.2; terminals retain the 51x19 grid. Both Minecraft targets use linear font filtering.
  Bitmap-font selection is removed and old preferences no longer affect rendering.

### Computer runtime

- Compiled programs share repeated debug source paths and store consecutive identical GC root maps as ranges,
  reducing executable size while retaining source locations, inline diagnostics, module identity and every GC boundary.
  Compact artifacts require the updated native Runtime reader; old artifacts remain readable.
- Running base, Sable and Propulsion computers hibernate across carrier unload/reload and orderly server shutdown, preserving
  execution, nested programs, unsaved editor buffers, terminal state, timers and filesystem handles. Restoration validates
  compatibility; failed capture or restoration logs the error and falls back to a fresh boot, keeping computers usable
  while retaining ComputerId and `/home`. `/compukters reboot x y z` also explicitly starts over. Sable physics continues
  while computers restore. Propulsion restores matching engine handles, owned throttle, steering and thrust overrides
  before execution resumes, with stale replacement handles and rollback on control conflicts. Thrust may lapse during
  unload/restoration. An unused Create addon permits capture; active Create resources remain unsupported.
- `Ctrl+T` in a computer or IDE target terminal stops the foreground command and its nested processes/tasks,
  releases peripheral control and returns to the shell while retaining output and files. Repeats are ignored;
  an idle shell remains running.
- Redstone inputs are isolated from passive conduction through the chassis. Explicit weak/direct outputs remain
  available, and input sampled during asynchronous startup is retained until the VM can receive it.
- Ready VM results notify a coalesced server-thread handler. Eligible world/addon completions can resume within
  the same tick using remaining instruction credit, the original deadline and cumulative per-tick limits.
  Regular tick delivery remains the fallback; shutdown invalidates stale notifications.
- Runnable computers share calibrated server-wide instruction capacity with rotating reservations under overload.
  Waiting computers rejoin after input or completion; `vmbench status` reports capacity and throttling counters.
  Runtime epochs remain unique across block-entity replacement, even when `ComputerId` is retained.
- Compiler packages and target metadata are prepared during world startup, keeping extraction out of ordinary
  computer ticks. Precomputed type/dispatch tables, compact object headers, temporary-frame reuse and direct
  immutable captures reduce runtime overhead and memory use. The allocator reuses fitting free blocks without
  rejecting them solely because of size-class rounding.
- OOM, Guest traps, VM faults and uncaught exceptions include bounded source stacks with library and user frames.
  Heap exhaustion also reports the executable, heap limit, allocation size, usage and GC status before returning
  control to the shell. Older artifacts fall back to available UTF-16 or bytecode positions.

<a id="build-tooling"></a>

### Documentation and build tooling

- The Wiki separates players, developers and contributors, with expanding header navigation, contextual sidebars
  and a shared addon catalog. README content and project/addon/dev-stand guides live on canonical site pages.
  Architecture, Kotlin and stdlib references now have short indexes and focused topic pages, with shared routing
  for contributors and agents. Syntax colors are generated from the in-game IDE palette.
- The Dokka reference includes core, Create, Sable and Propulsion APIs, with shared target naming and source links.
  Its independent Gradle project lives in `api-build/`.
- `addons/dev` runs the first-party addons with Aeronautics, Simulated, Offroad and Propulsion: Simulated using
  workspace SDK/platform inputs. The independent Create GameTest server covers kinetics, boilers, naming,
  reconnects and stale handles without introducing Create into base-mod tests.
- Runtime and NeoForge dependencies are updated within the existing Minecraft 1.21.1 and 26.1.2 targets.
  Tomlj 2.1.1 carries its private ANTLR runtime; Kotlin remains pinned at 2.4.10. Main, addon and API-documentation
  wrappers use Gradle 9.8.0; documentation CI uses JDK 27 while production toolchains remain Java 21 and 25.

### Upgrade notes

- `IntChannel` is removed from the Guest API. Rework programs that import it; a generic channel replacement
  is deferred. Cooperative tasks and joins remain available.
- Rebuild Guest programs and compiled platform/addon libraries for the updated platform. MFVC inline layouts
  require Runtime ABI 1.15; unsigned operations and compact primitive storage require 1.14, and structured host
  results require 1.13. Native Runtime 0.20.0 supplies C ABI 20 for both transports. Base bundles/modules use formats 9/5 and platform ABI 3.
- The built-in libraries are `kotlin:builtins` 1.9.0, `stdlib:core` 1.11.0 and `compukter:core` 2.0.0.
  Addons depending on removed split-module IDs must rebuild against the consolidated owners.
- Legacy display/Create/Propulsion acquisition helpers and separate side types are removed. Use typed device
  providers and `compukter.peripheral.Side`. Create and Propulsion use addon API line 2.0; the typed-contract
  registration SDK is 0.5.0.

## 0.4.0 — 2026-09-12

This release makes the integrated multi-file Kotlin IDE available on both supported Minecraft versions, adds a Java
21 / NeoForge 1.21.1 build, and moves computer execution onto the bounded asynchronous Runtime 0.12 architecture.

### Guest Kotlin

- Added bounded cooperative tasks through `Tasks.launch`, task handles, and `join`.
- Added VM-owned bounded `IntChannel` communication between Guest tasks with suspending `send(Int)` and `receive()`.
- Added immutable top-level Guest state, including scalar constants and channel declarations.
- Added default `Int` arguments to platform calls.
- Added bounded computer beeps through the Guest sound API.

### Runtime and computers

- Added a NeoForge 1.21.1 / Java 21 port backed by the JNI runtime transport; the existing 26.1.2 build continues to
  use JDK 25 FFM.
- Fixed 1.21.1 computer startup on Java 21 and restored its block and item models and crisp terminal rendering.
- Unified the terminal panel, border, background, title, and resource-gauge palette across both supported Minecraft versions.
- Moved production computers onto the asynchronous actor runtime with bounded admission, worker execution, result
  delivery, and clean close barriers.
- Moved Guest task scheduling and channel handoff into the Rust VM; channel traffic does not make a server request.
- Preserved redstone transitions while VM work is in flight and aligned redstone and sound effects with fixed server
  tick phases.
- Allowed terminal, compiler, and redstone waits to coexist with host operations from other Guest tasks without
  starvation or missed redstone edges while another task publishes output.
- Restored live terminal observation after leaving the IDE.
- Reduced the default Guest heap and removed unused runtime tracing overhead.
- Improved VM register access, host continuation delivery, result pumping, and scheduler capacity.
- Added terminal gauges for observed VM CPU utilization, heap use, and resident execution state.

### Profiling

- Added the packaged `vmbench` program and in-world controls for deterministic headless VM fleets.
- Added CPU and capacity benchmark modes, automatic status output, scheduler/result metrics, phased sampling, physical
  computer area fanout, and a redstone pulse workload.
- Raised the supported actor capacity to 4096 and allowed benchmark areas larger than 1000 computers.

### In-game IDE and tooling

- Ported the integrated IDE and its icon toolbar to Minecraft 1.21.1 while retaining version-specific screen and
  rendering adapters.
- Added remembered project workspaces and a project switcher.
- Added parameter information and replaced IDE toolbar labels with icons.
- Fixed analysis failures involving synthetic Kotlin declarations.
- Reused verified Kotlin worker caches and reduced packaged tooling size with solid XZ compression and shared compiler
  files.

### Persistence and verification

- Moved bounded filesystem persistence work off the server thread and strengthened store shutdown, failure isolation,
  stale-lock recovery, and crash-point coverage.
- Added complete native FFI symbol/descriptor parity checks and expanded Kotlin-to-VM conformance coverage.
- Added pinned dual-transport Runtime bundles and release gates for both the Java 25 FFM and Java 21 JNI artifacts.
- Non-interactive agent Gradle runs now keep their complete logs in `build/agent-logs/` and print a concise summary.
- Added this continuous repository-owned changelog as a permanent page on the documentation site.

### Compatibility notes

- Minecraft 1.21.1 with NeoForge 21.1.250 or newer is supported on Java 21. Its initial compatibility build includes
  computers, persistence, redstone, sound, compilation, the terminal, the integrated IDE, and VM benchmark commands.
- Channel-enabled executables use artifact/runtime contract 1.2 and require the matching 0.4 runtime. Older artifacts
  whose channel limits are zero remain valid under the extended contract.
- Minecraft 26.1.2 with NeoForge 26.1.2.97 or newer remains the primary Java 25 baseline.

[GitHub release](https://github.com/CertifiedBadIdeas/Compukters/releases/tag/v0.4.0) ·
[Compare v0.3.0...v0.4.0](https://github.com/CertifiedBadIdeas/Compukters/compare/v0.3.0...v0.4.0)

## 0.3.0 — 2026-09-06

This release focused on programmable redstone I/O, a native Compukters K2 platform, a richer in-game IDE, and a
substantially reworked managed runtime.

### Redstone GPIO

- Added first-class analog redstone input and output on all six computer-local sides.
- Added suspending waits for the next change, an exact level, or a minimum level.
- Added weak and direct output power modes.
- Made outputs persistent across program completion, reboot, VM faults, VM replacement, and chunk reload.
- Sampled and coalesced input changes at Minecraft tick boundaries without Guest-side polling.

### Guest Kotlin

- Replaced the JVM-bootstrap Guest environment with a native Compukters K2 platform.
- Made compiler and IDE analysis consume the same explicit platform metadata and source declarations.
- Added modular Guest platform libraries selected through `compukter.toml`.
- Added allocation-free primitive-backed value classes, scalar string interpolation, bounded `Int` ranges, `break`,
  `continue`, and specialized `IntArray` operations.
- Added lazy type initialization for enum and platform-library static state.

### In-game IDE

- Added semantic hover, Go to Declaration, source navigation history, and read-only platform source views.
- Added context-aware keyword completion, explicit Kotlin reformatting, automatic delimiter pairing, structural editing,
  copy/cut, word navigation, page navigation, multi-line indentation, and double-click selection.
- Improved syntax highlighting, Unicode handling, caret behavior, incremental analysis, and analysis diagnostics.

### Runtime, compiler, and verification

- Updated to Compukter Runtime 0.9.0 with FFI ABI 9.
- Reworked VM frames, compact Guest references, exact GC safepoint liveness, memory accounting, and managed reference
  storage.
- Changed host-request limits into tick-scoped backpressure for valid long-running I/O programs.
- Improved linking, reachability, dead-code elimination, artifact validation, and compiler/VM conformance coverage.
- Expanded `verifyLocalFull` to every Gradle subproject, Rust/FFI checks, runtime integration, GameTests, and the
  production JAR while keeping tagged multi-platform release verification separate.

### Breaking changes

- Executables from 0.2.x must be rebuilt from Kotlin source for the 0.3 artifact/runtime contract.
- Projects using the former JVM-bootstrap Guest platform may need their dependency lock resolved again.
- Guest Kotlin exposes only declarations provided by selected Compukters platform modules, not the arbitrary JVM
  Kotlin standard library.

[GitHub release](https://github.com/CertifiedBadIdeas/Compukters/releases/tag/v0.3.0) ·
[Compare v0.2.0...v0.3.0](https://github.com/CertifiedBadIdeas/Compukters/compare/v0.2.0...v0.3.0)

## 0.2.0 — 2026-08-29

### In-game IDE

- Added build, verify, deploy, and run support for attached computers.
- Added an interactive target terminal and a read-only filesystem explorer with file preview and import.
- Added overload/signature completion and automatic completion-list scrolling.
- Moved Kotlin tooling initialization off the render thread and improved initial focus and semantic highlighting.

### Terminal and UI

- Added an IDE button and `Ctrl+I` shortcut to the computer terminal.
- Made Compukters UI scaling independent of Minecraft GUI scale.
- Improved bitmap-font rendering and the IDE terminal layout on smaller screens.
- Fixed delayed terminal submission, terminal ownership handoff, dialog layering, and font-change layout issues.

### Computers, runtime, and distribution

- Restored the blue workbench-style computer appearance.
- Added secure revision-aware executable deployment and improved computer, IDE, and server-VM communication.
- Updated to Compukter Runtime 0.5.1.
- Added pinned and verified Linux and Windows runtime bundles, shared deterministic Kotlin tooling, and stronger
  production JAR assembly and release checks.

[GitHub release](https://github.com/CertifiedBadIdeas/Compukters/releases/tag/v0.2.0).
