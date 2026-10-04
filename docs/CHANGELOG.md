---
layout: default
title: Changelog
description: User-visible changes in every Compukters release.
permalink: /CHANGELOG/
---

# Changelog

This page records user-visible Compukters changes. The newest version is first; releases remain as permanent section
headings so this page has one stable URL that can be shared outside the repository.

## 0.5.0 — In development

This release expands the Kotlin available to computer programs and introduces support for independently installed
addon mods, with optional Create, Sable and Propulsion integrations for Minecraft 1.21.1.

- Optional **Compukters: Propulsion** adds named Creative Thruster and Creative Vector Thruster control through
  peripheral cables: normalized Double throttle, local vector steering, absolute vector-engine thrust in kN,
  saved ordinary-engine thrust percentage and typed state snapshots. One program owns control; stopping,
  disconnection or computer removal clears digital throttle, steering and custom vector thrust and restores current
  redstone/link inputs without a residual program shutdown envelope or a thrust spike during handoff.
  Explicit close releases control early. Chunk saves and construction copies retain engine configuration without transferring a program's digital
  command. The independent addon joins Create/Sable and the pinned Aeronautics/Propulsion runtime in the shared dev stand.

### Guest Kotlin

- Scalar value classes based on Int, Boolean and Char can be stored in lists and generic arrays, passed as Any,
  and used as nullable values. Managed wrappers retain the nominal type between Guest code and precompiled addon
  libraries; equality, hashCode, checked casts and toString preserve value-class semantics. Direct typed calls
  remain unboxed. Creative Vector Thruster handles can be grouped with listOf and controlled through forEach.
  Rebuild platform/addon bundles and Guest programs; the artifact format and native ABI are unchanged.

- Addon resources now belong to individual Guest programs: completion releases their devices while suspended
  parent programs retain theirs. Native transports require bundled C ABI 20.

- Guest compilation with addons selects the canonical owner of collection specializations even when an addon bundle
  contains a pruned dependency copy. Conflicting standalone owners remain rejected.

- Generated addon handlers can return Long, Double, Char and bounded immutable nested data records. The SDK
  generates typed host DTOs and validates the record shape; the VM copies and materializes responses under its
  existing budgets. Double IEEE bits and UTF-16 code units are preserved. Structured responses require
  Runtime ABI 1.13 and the bundled native C ABI 20; rebuild addon bundles and Guest programs.

- Programs can use Double with F64 arithmetic, mixed numeric operations, conversions, constants,
  nullable and generic values, console/text output, equality and hashCode. DoubleArray stores F64 elements
  without boxing and supports indexed access, iteration and copying. Double text and hashing require
  Runtime ABI 1.12; rebuild platform bundles, programs and compiled libraries.

- Programs can throw and catch exceptions through nested calls, match user-defined exception subclasses,
  rethrow the original object, and read nullable `message` and `cause`. `finally` runs on normal and exceptional
  exits, returns and supported loop/inline exits. Uncaught exceptions report their class,
  message and bounded source stack.
  `require`, `check` and `error` use catchable exceptions, with stdlib-compatible lazy `() -> Any` messages for the preconditions.
  Integer division/remainder, array and string bounds, negative array sizes, null references and checked casts
  throw typed catchable exceptions. Invalid channel arguments are catchable; resource exhaustion remains terminal.
  Ordinary host EOF/I/O and unavailable-operation failures are catchable as IOException and IllegalStateException,
  including across task suspension and filesystem reads; cancellation and VM resource failures remain terminal.
  Standard exception descendants and `compukter.io.IOException` use ordinary library constructors,
  preserving nullable causes through superclass calls. Platform bundles and standalone modules need rebuilding
  for bundle format 9 / module format 5.
  Exhaustive `when` can return references without an explicit `else`.

- `toString()`, string templates and string concatenation support scalar, String, Unit, nullable and `Any`
  values, with virtual user-defined and inherited overrides across compiled libraries. Default object and array
  text includes the qualified runtime type and stable VM identity. Supported scalars can pass through `Any`
  with value equality and checked casts. Lazy assertion messages run and convert only on failure;
  exceptions from their bodies or `toString` remain catchable.
- `hashCode()` supports scalars, UTF-16 strings, nullable and `Any` values, with virtual user-defined and inherited
  overrides across compiled libraries. Default object and array hashes retain their identity across GC;
  supported data-class properties receive generated value hashing. Null hashes to zero, and boxed Float hashing
  agrees with value equality for NaN and signed zero. Rebuild programs and libraries for Runtime ABI 1.11.

- `equals`, `==` and `!=` use virtual value equality across compiled libraries, including custom/inherited and
  superclass implementations. Data classes compare nullable, object, Float and Double constructor properties;
  array properties retain reference equality. Receiver/argument effects run once and exceptions remain catchable.
  The shared equality/hashCode contract reuses Runtime ABI 1.11; compiled libraries need rebuilding.

- Reference identity comparisons (`===`/`!==`) compare supported arrays and nullable references directly,
  preserving aliases and distinguishing fresh copies without artificial casts to `Any`.
  Supported arrays can also pass through `Any` and `Any?` without copying or boxing, preserving their runtime
  type for type tests and reverse casts. Previously compiled libraries containing arrays need rebuilding for
  these conversions.

- Programs can copy supported arrays with public `copyOf` and `copyInto`, including resizing, null/zero padding,
  and overlapping ranges. Native bulk copying respects execution budgets without temporary buffers; `ArrayList`
  growth uses the same APIs. Platform bundle/module formats now preserve receiver-dependent default arguments;
  independently packaged Guest platform modules need rebuilding.

- OOM, Guest traps and VM faults include bounded call stacks with source files, lines and columns in normal builds,
  preserving library frames and nested user callers. Older programs fall back to UTF-16 or bytecode positions; a
  foreground child failure returns control to the shell.

- Guest libraries can combine precompiled ordinary implementations with generic and inline source bodies in one
  module, preserving private and internal visibility and avoiding duplicate ordinary implementations. Core helpers,
  scope functions, `repeat`, ranges and collections share one `stdlib:core` module without making its ordinary
  implementations source-only.
- Concrete generic types materialized by ordinary library implementations can be reused by programs and dependent
  libraries, sharing their constructors, fields and methods without duplicating nominal types. New concrete variants
  continue to specialize from source templates.
- Runtime and environment APIs share one `compukter:core` module: compiler, processes, terminal, filesystem,
  cooperative tasks/channels, redstone, sound and displays. Kotlin packages and imports remain unchanged; addons
  depending on the removed split-module IDs need to rebuild against the consolidated owners.
- Programs can use the standard `let`, `run`, `with`, `apply`, `also`, `takeIf`, and `takeUnless` scope functions
  with inline lambdas, including supported nullable receivers and non-local returns.
- Programs can traverse Guest iterables with `forEach` and `forEachIndexed`, or run an indexed action with `repeat`,
  using inline callbacks and non-local returns.

- Newly compiled programs reuse memory for temporary values with nonoverlapping lifetimes, reducing execution frame
  requirements while preserving types, GC roots and suspended calls.
- Managed allocations can reuse fitting contiguous free blocks even within partial allocator size classes;
  post-GC retries retain bounded execution without rejecting a block solely because of size-class rounding.

- Programs can declare supported top-level and extension `inline` functions, including concrete generic callbacks
  and non-local returns. Direct callbacks can avoid closure allocation; stored or escaping callbacks retain managed
  ownership. Callback-taking collection extensions also inline direct lambdas and support non-local returns.
  Expansion has compiler safety limits.

- Captured local `var` values that are never reassigned use direct closure fields, avoiding an extra heap cell
  while preserving shared state for mutable captures.

- Programs can reuse mutable collection buffers through `mapTo`, `filterTo` and `mapNotNullTo`, which append
  to the supplied destination and return it.

- Programs can use `mapNotNull` to transform and retain non-null results in one pass without an intermediate list.

- `map` on supported lists and statically typed collections reserves the input size for its result, reducing
  temporary backing arrays and peak heap use while preserving iteration order and element identity.
- Programs can search strings by UTF-16 code unit with `startsWith`, `endsWith`, `contains`, and `indexOf`, including
  an optional starting index for `indexOf`, and parse decimal input with `String.toIntOrNull()`.
- String helpers include Unicode-whitespace blank checks and trimming, Char search and backward `lastIndexOf`,
  delimiter-based substring extraction with optional fallback, prefix/suffix removal, Char/String splitting with
  limits, CRLF/LF/CR line splitting, and case-sensitive Char/String replacement. Splitting retains empty parts;
  replacement checks output-length overflow and avoids repeated string concatenation. Completion and hover resolve
  the concrete overload signatures and their documentation.
- Programs can use nullable strings and supported class references, compare them with `null`, and use `?:` or
  reference-result `?.` without evaluating the unused branch. Nullable `Int` values use managed boxes, support equality
  and Elvis, and allow Int-result safe calls such as `text?.length`. Other nullable primitives remain unsupported.
- Programs can use direct generic functions and final generic classes with typed constructor fields and direct methods.
  The compiler specializes each concrete use, retaining unboxed non-null scalar fields and calls. Source-only generic
  library modules, including classes with methods, can be specialized in a consuming program.
- Programs can create `Array<T>` values for supported non-null Guest classes with `arrayOf` and `emptyArray`, then read
  or replace elements by index. Concrete uses inside specialized generic functions retain their element types. Mixed
  `Array<Any>` values can hold `Int`, strings, and supported objects; `Int` values are boxed when stored.
- Programs can create read-only `List<T>` views of fresh `ArrayList<T>` instances with `listOf` and `emptyList`, read
  `size` and indexed elements, and iterate with `for`. Read-only views can be cast to `MutableList<T>` or `ArrayList<T>`
  to modify the same object; factory lists use the same index validation and iterator mutation checks as other
  `ArrayList` values. Supported elements include `Int`, `String`, Guest class references, and their nullable forms; `List<Int>`
  keeps unboxed storage and typed reads, while `List<Int?>` stores boxes or null. Lists can widen to `List<Any?>`
  while preserving null, element references, and value searches. A `List<Int>` can also be used as `List<Any>` without copying the list; reads
  through that view produce boxed `Int` values that can be checked with `is Int` and cast back with `as Int`. Supported
  reference lists also widen to `List<Any>` while retaining their element references. Programs can construct a mixed
  `List<Any>` directly from `Int`, strings, and supported objects; its `Int` elements are boxed when the list is built.
  Values held as `Any` can be compared with `==` or `equals`: boxed `Int` compares by value, strings by content,
  supported data classes compare their constructor properties, and Guest classes honor explicit `equals` overrides.
  Other classes use default identity equality. Read-only collections expose `size`, `isEmpty`, `isNotEmpty`, and
  `contains` / `in`; lists provide `indexOf` and `lastIndexOf` as indexed methods. General `Iterable<T>` values
  support `contains`, `indexOf`, `lastIndexOf`, and `any`, `all`, `none` with predicates, including user-defined iterables.
  `firstOrNull` and `lastOrNull` select elements with optional predicates; `List.getOrNull` returns null for invalid
  indexes. These operations support nullable elements and `Int?` results. `Iterable.fold` accumulates in iteration
  order with independent element and accumulator types, including nullable values and Guest class accumulators.
  `Iterable.map` transforms elements into a new list in iteration order, supporting independent input/output types,
  nullable values, and Guest classes. `Iterable.filter` selects matching elements into a new list while preserving
  order, duplicates, nullable types, and stored references. `Iterable.filterNotNull` removes nulls and returns a list
  with non-null elements, including unboxed `Int` results from `Int?` inputs. Library lists also support read-only
  nullable element views without copying their storage.
- Guest programs can create `ArrayList<T>` and use it through `MutableList<T>` to add, insert, replace, remove, and
  clear elements, including removal through mutable iterators. Lists grow within VM memory quotas, use unboxed `Int`
  storage, support nullable elements, and share changes with read-only list views. `arrayOfNulls<T>(size)` creates
  null-filled arrays for supported element types.
- Computer programs can use unboxed `Long` and `Float` values, including mixed numeric arithmetic and comparisons,
  direct `compareTo` calls between `Int` and `Long`, explicit conversions, `Long` bitwise and shift operations,
  constants, string interpolation, and console output.
- `Float` values retain their binary32 representation through arithmetic, equality, host responses, and conversion to
  text, including signed zero, infinities, NaN, and subnormal values. Direct `compareTo` calls with `Float`, `Int`, or
  `Long` use Kotlin's total order for NaN and signed zero.
- Strings support direct `compareTo` calls and `<`, `<=`, `>`, `>=` operators using UTF-16 lexicographic order.
- `Char.compareTo` returns the UTF-16 code-unit difference; `Boolean.compareTo` and Boolean ordering operators use
  `false < true`.
- Operations that wait for the world, another task, or a channel now block transparently inside ordinary functions;
  source-level `suspend` declarations are not part of the supported Guest Kotlin subset.
- `Tasks.sleepTicks(n)` lets a program pace world interactions by server ticks while other Guest tasks continue and
  without spending its instruction budget during the wait.
- Guest classes and interfaces support ordinary non-suspending instance methods, overrides, and runtime class or
  interface dispatch. Primary-constructor `var` properties can be assigned through object references and instance
  methods; aliases observe the updated field. Class-body properties and `init` blocks run in source order after the
  superclass initializer on the same object, including construction through a constructor reference. Primary
  constructors can use Guest expressions for default arguments in ordinary calls, including values derived from
  earlier parameters; explicit arguments run before omitted defaults. Computed class
  properties and custom getters/setters can read or update backing fields and dispatch through base-class references.
  Abstract class and interface `val`/`var` properties dispatch to concrete implementations without storing fields in
  their abstract declarations. Interfaces can also provide default method bodies and computed property accessors;
  classes inherit them unless they override the member. An override can call a chosen interface implementation with
  `super<Interface>`, including methods inherited by that interface.
- Guest programs can use sealed interfaces, data class values, and stateless enums for type branches, property reads,
  and enum identity comparisons.
- `Int` `for` loops support `downTo` and positive `step`, including dynamic steps and integer boundary values.
- `IntArray` values can be traversed directly with `for`, including empty arrays and in-loop element updates.
- Non-null function values with Guest-supported parameter and result types can be passed, returned, stored locally,
  and invoked without a fixed two-argument or JVM 22/23 cut-off. Function-value aliases preserve referential identity
  comparisons. Lambdas use ordinary managed closure objects, preserve reference aliasing for immutable captures, and share mutations through unboxed
  typed cells when local `var` values are captured, including across nested lambdas. `Tasks.launch` accepts
  `() -> Unit` values as bounded cooperative tasks. Unbound references to Guest top-level functions can be passed,
  returned, stored, and invoked as typed function values. Bound Guest instance-method references retain their receiver
  and preserve virtual and interface dispatch. Unbound `Type::method` references accept the receiver as their first
  argument and use the same dispatch. References to supported Guest class constructors can also be passed, returned,
  stored, and invoked as typed function values, including references adapted to omit supported trailing constructor
  defaults.

### Addons, Create and Sable

- Independently installed addon mods can provide typed Guest Kotlin APIs without becoming dependencies of Compukters.
  An independently versioned Gradle SDK generates their stable ABI lock, typed host contract, capability schema,
  bindings and packaged Guest API bundle from Kotlin declarations; addon authors do not maintain numeric operation IDs
  or wire decoding. Its Minecraft-independent host API and thin version-specific NeoForge adapter remain compile-only,
  so ordinary Compukters releases do not force an SDK version update or expose implementation classes. The compiler
  and IDE expose only the addons available on the attached server.
- Addon projects register one atomic Guest API by addon ID; its version defaults to the addon's Gradle project version,
  while platform dependencies and capability wiring remain internal. Compukters projects list only addon IDs in
  `compukter.toml`, with exact versions and hashes retained in `compukter.lock`.
  The computer's `kotlinc` command uses the same full module identities advertised by the server for addon compilation.
  IDE compilation includes only the project's selected addon modules and their matching API bundles when several
  addons are installed together.
- The separately installed Create addon supports Create 6.0.x on Minecraft 1.21.1 while the base Compukters mod remains
  usable without Create.
- The independent Sable addon exposes `sable.physics.Physics.snapshot()` for computers on constructions. One request
  returns a typed copy of identity, dimension, world tick, paused state, logical pose and solver velocities using
  Double values. Unavailable constructions throw a catchable IllegalStateException. Observation runs only on request
  and retains the existing VM cadence and budget.
- Added passive peripheral cables for orthogonal, branching and looping connections between computers and supported
  addon devices. Their thin model follows the actual connections in all six directions. Cables discover loaded chunks
  only and do not require adapters or a controller block.
- Added a one-block text display to the base mod. Guest programs can address it beside the computer or by name over
  peripheral cables, write to an independent 20x10 grid, and clear it. One computer controls a display at a time; the
  screen clears when its writer stops or disconnects.
- Added a Peripheral Configurator editor that opens on supported devices and inspects their bounded cable network. It
  reports available and duplicate names before assignment, while using it on a cable opens a read-only list of every
  connected device and its name when assigned. The server rejects stale or conflicting changes; Shift-use clears a
  name. The configurator item remains unnamed, while device names persist with the world independently of computers
  and cable topology.
- The `create` addon reads exact `Float` speed, stress, and capacity values from adjacent or named cable-connected
  speedometers and stressometers, waits for value changes without Guest-side polling, and controls rotation speed
  controllers.
- The `create` addon also exposes adjacent or named Stock Tickers. Computer programs can search bounded stock snapshots
  by item ID, distinguish exact item variants, and request packaging to an address; acceptance is reported without
  implying delivery.
- The `create` addon monitors active steam boilers through any Fluid Tank segment, reporting sampled water supply in
  mB/t, the water gauge level, active heat, effective boiler level, and passive heating.
  Named discovery follows boiler activation changes even when a changed steam engine does not touch the cable.
- Device handles remain bound to the exact acquired block, and named handles expire when their cable path disconnects.
  Missing, disconnected, unloaded, removed, or replaced devices fail deterministically and produce a descriptive
  terminal diagnostic.
- Addon SDK 0.4.0 uses platform ABI 3; addon bundles must be rebuilt against the new base platform. It lets
  independent addons map a cable touching any supported block or multiblock part to one canonical
  logical device, validate retained handles against current reachability, and preserve the existing adjacent-side
  registration API.

### In-game IDE

- The editor subtly highlights the current line beneath selections and occurrence marks, independently of caret blinking.
- Returning to a project file preserves its caret, viewport and undo history within a bounded document cache.
- `Alt+F7` opens semantic Find Usages with file/line context, clickable results and keyboard navigation; source edits invalidate stale results.
- IDE problems have clickable source locations, severity markers beside line numbers, error/warning counts in the
  status bar, and cyclic `F2` / `Shift+F2` navigation. Build locations require matching source text before navigation;
  outdated messages remain visible without applying stale offsets.
- Method declarations show clickable non-zero project usage counts on the same line, distinguishing overloads and
  unrelated same-named methods. Counts remain visible during analysis of edits that leave the method name untouched.
- `Shift+F6` safely renames project symbols across Kotlin files, including unopened files and unsaved buffers.
  K2 checks name collisions and reference resolution before applying one undoable edit; `Ctrl+Z` and redo cover
  every affected file. Library symbols, the `main` entry point and inheritance/convention-based declarations remain
  read-only for this action, and projects must have no analysis errors.
- Placing the text caret on an analyzed Kotlin identifier highlights its declaration and references in the current project file,
  distinguishing overloads and unrelated same-named symbols, including references inside string interpolation.
  Typing hides symbol occurrences until the cursor is explicitly placed or moved again, preventing highlights from
  appearing or flashing during text entry.
  Selecting text highlights its literal occurrences everywhere: from two characters inside strings and from three
  elsewhere, also in read-only files. Symbol occurrences do not wait for hover and are independent of mouse movement.
  Explicit Ctrl+F results take priority while search is open.
- Ctrl+F searches the current file with literal, case-sensitive matching, highlighted results and a match counter.
  Enter/Shift+Enter cycle through results; Escape closes search. Search also supports read-only computer and API files.
- IDE panels use contrasting dark surfaces, wider draggable dividers and soft shadows on popups. Automatic IDE
  scaling is one step smaller (minimum 2), leaving more space for code without changing terminal scaling.
- The code editor, completion list, hover information, terminal windows and in-world text displays use
  bundled JetBrains Mono without ligatures. The terminal keeps its 51x19 grid; bitmap fonts and the font
  selector are removed, and old font preferences no longer affect rendering. Editor line spacing is 1.2;
  terminal cells retain their dimensions. Both Minecraft targets use linear texture filtering for this font
  without changing the standard Minecraft UI font.
- Member completion includes the supported operations of built-in Guest Kotlin numeric types.
- Function completion inserts call parentheses or a trailing lambda block and places the caret at the first
  required input. Existing delimiters are reused; autoimports and caret placement undo and redo together.
  Functions with a single lambda argument show a brace signature with the argument name and function type,
  omitting names of parameters inside the lambda type.
  Completion rows use colored F/M/V/C/I kind badges, show receiver and package context beside callable signatures,
  and align receiver-specialized return types in a separate right-hand column, including scope functions
  such as `also` and `apply` immediately after typing a receiver's dot or safe-call operator,
  on literals and in generic call chains. Independent lambda-result types remain symbolic until known.
  Identifier completion supports case-insensitive contiguous word-fragment and CamelCase matching, including autoimports;
  direct prefixes rank first, without fuzzy typo correction or arbitrary letter skipping inside words.
  Completion highlights the matching name fragments without changing aligned return types. Hover shows wrapped KDoc
  from resolved project declarations and attached library sources beneath signatures and types, with bounded popup height.
  Diagnostic passes pause during completion while semantic analysis continues; existing unrelated problems remain
  visible and diagnostics resume for the current text when completion ends.
  Enter inside an empty lambda adds an indented body line and moves its closing brace to a separate line.
  Typing a function declaration name does not open automatic completion; explicit completion remains available.
- Completion suggests visible variables in string interpolation immediately after `$` and while typing the name.
- Completion, automatic imports, parameter information, and source navigation include compatible APIs supplied by
  installed addons. Selecting `Kinetics` or `Logistics` also enables the `create` addon for the project.

### Computer runtime

- Runtime attachment epochs remain unique when a computer block entity is replaced while preserving its
  ComputerId, preventing an old actor address from matching the new runtime.

- Foreground programs that exhaust their Guest heap report the executable path, heap limit, allocation size,
  memory usage and GC status before returning control to the shell. Verified debug metadata supplies the source
  path and UTF-16 offset when available; otherwise diagnostics identify the function and bytecode location.

- Small Guest Kotlin objects use less heap through a compact eight-byte VM header and eight-byte block alignment.
  Empty, one-`Int`, and two-`Int` objects, including boxed `Int` values, occupy 16 bytes in the 256 KiB Guest heap;
  three-`Int` objects occupy 24 bytes. Ordinary references, shared mutations, and identity are preserved. In
  measured 4096-record workloads, deep copies and transformations retaining their input fit that heap.
- Active computers now share a calibrated server-wide Guest instruction capacity each tick. Reservations rotate
  fairly between computers under overload, while waiting computers leave the runnable pool until input or a world
  completion wakes them. Server operators can inspect the capacity and throttling counters with `vmbench status`.

### Build tooling

- Contributors can launch and test the independent Create and Sable addons together with Aeronautics, Simulated,
  Offroad and Propulsion: Simulated from `addons/dev`. The stand uses
  the current workspace SDK and platform bundle and produces no distributable umbrella mod.

- The standalone Create addon has an independent GameTest server for real kinetic devices and boilers, exercising
  Guest programs, peripheral naming, cable reconnection and stale handles without adding Create to base-mod tests.
- Runtime dependencies and NeoForge are updated within the existing Minecraft 1.21.1 and 26.1.2 targets;
  Tomlj 2.1.1 now carries its own private ANTLR runtime instead of a separate JAR. Kotlin remains pinned at 2.4.10.
- Main, Create addon and API documentation Gradle wrappers use 9.8.0, which supports running Gradle on JDK 27.
  Compilation toolchains and JVM targets remain unchanged at Java 21 and 25.
- Documentation CI runs on Temurin JDK 27.
- The independent API documentation Gradle project lives at the repository root in `api-build/`.

## 0.4.0 — 2026-09-12

This release makes the integrated multi-file Kotlin IDE available on both supported Minecraft versions, adds a Java
21 / NeoForge 1.21.1 build, and moves computer execution onto the bounded asynchronous Runtime 0.12 architecture.

### Guest Kotlin

- Guest compilation with addons selects the canonical owner of collection specializations even when an addon bundle
  contains a pruned dependency copy. Conflicting standalone owners remain rejected.

- Generated addon handlers can return Long, Double and Char, preserving Double IEEE bits and exact UTF-16 code units
  through both native transports. This requires the bundled native C ABI 19.

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

- Guest compilation with addons selects the canonical owner of collection specializations even when an addon bundle
  contains a pruned dependency copy. Conflicting standalone owners remain rejected.

- Generated addon handlers can return Long, Double and Char, preserving Double IEEE bits and exact UTF-16 code units
  through both native transports. This requires the bundled native C ABI 19.

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
