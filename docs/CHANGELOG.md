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

## 0.6.0 — In development

- The addon development stand loads optional Connector, Forgified Fabric API, Sodium, Voxy and Voxy Worldgen through Gradle;
  their dependency lines can be commented out. Loom supplies Connector's clean Minecraft path and remaps every
  Fabric API module for the development environment.

- Immutable Guest records now use native value classes throughout the platform API, addon observations, system
  programs and examples, including terminal events, process/compiler results and boiler telemetry. Recompile
  Guest programs against the updated platform and addon bundles. Boiler controller checks execute in the Guest VM.

- Added an empty independent Cosmonautics addon scaffold and pinned Cosmonautics in the all-addon development stand;
  Guest APIs and spacecraft controls will follow separately. The existing Crafts & Additions addon is also published
  independently and pinned as a submodule.

- The Crafts & Additions integration exposes named liquid Blaze Burners with atomic fuel quantity, capacity,
  heat and remaining burn-time readings. The separate `boiler-scada` project starts water/fuel pumps from a
  kinetic battery, switches to steam self-power with battery charging, and reverses the fuel pump on STOP until
  every configured burner is empty. Its panel shows boiler readings, per-burner fuel, cooldown and fault states.
  The development stand includes Crafts & Additions and Create: Connected.

- Task worktree completion checks active builds in nested independent Gradle projects and excludes their
  sandbox caches from its archive while retaining worlds and IDE data.

- Guest Kotlin supports direct generic methods on ordinary classes and objects, combining class and method
  type specialization with defaults, local helpers and escaping callbacks.

- Guest generic classes support callbacks and local helpers in primary-constructor initialization, field
  initializers and `init` blocks, with concrete capture types and independent state for each instance.

- Added terminal history for the last 512 scrolled rows, available after reopening, with mouse-wheel and keyboard navigation, rectangular selection/copy, and ordinary input paste in standalone and IDE terminals.

- Guest Kotlin enums expose immutable `EnumType.entries` in declaration order, including empty enums and enums from platform/addon libraries. Entries expose their declaration `name` and zero-based `ordinal`, including through `Enum<E>` and `Enum<*>` references. Indexing, iteration, membership and IDE completion share the typed `EnumEntries<E>` API.

- Joining a peripheral network assigns unnamed devices unique numbered names such as `display_1`;
  existing names are preserved and generated names remain stable across reloads.

- Create boilers appear as one whole structure in the configurator: every tank segment shares its controller's
  name and network, with one outline around the complete tank. Explicit interaction consolidates older per-block
  bindings; addon providers can opt into the same bounded structure geometry.

- Redstone Ports show input/output strength independently on all six faces, with small edge indicators visible when a face is covered.

- Newly compiled Guest programs allow 64 nested calls, matching the runtime limit, so framed nested UI drawing fits within the declared stack budget.

- The shell repeats the failure reason beneath multiline program diagnostics so it remains visible after a long stack trace.

- Guest Kotlin supports classic compile-time contracts for callback invocation counts, definite local initialization and conditional null/type smart casts. Contracts cover standard scope functions, preconditions, `buildString` and nested display UI construction; narrowed primitives retain explicit same-type casts and Elvis behavior. Declarations add no runtime builder or VM operations.

- Display frames record bounded drawing packets through `DisplayFrame`, reducing host round trips while
  retaining clipping, atomic publication and drawing quotas. Canvas/widgets/UI draw through the frame recorder.
- Guest displays provide a lightweight declarative widget DSL with explicit pixel viewports, rows/columns,
  reusable mutable widgets, a clipped drawing canvas and a rotation-slider cube example. Server-validated
  held-right-click gestures support captured dragging, release and cancellation without a reactive runtime;
  display clicks and dragging do not trigger the player's hand-swing animation.

- Typed peripherals are exclusive to the acquiring program, including reads and competing processes on one
  computer. Program exit and lost connectivity release ownership. The built-in Redstone Port provides remote
  six-face input sampling, asynchronous waits and weak/direct output control through peripheral networks,
  including atomic all-face writes with `Side.ALL` as the default port-level output selection;
  its transient outputs clear when control is released.

- All main, addon, development-stand and API-documentation builds use Gradle 9.8.1, with the binary distribution
  verified against its published SHA-256.

- The IDE has a named peripheral selection window with shared window controls, block item icons and project-style cards.
  Selected cards offer server-validated rename, typed lookup copying with an API choice for shared devices, and
  explicit undoable `const val` insertion beneath imports in the active Kotlin source. Rename rejects stale or replaced
  devices and name conflicts throughout the network. Constants use uppercase snake-case identifiers, duplicate reuse
  and collision suffixes; copying and rename leave existing source unchanged.

- Displays accept empty-hand front-face clicks in whole-screen pixel coordinates, exposed through
  `GraphicalDisplay.pollClick()`. Input belongs to the drawing program and is bounded and transient.
  Guest widgets provide buttons, indicators, gauges and history charts. The `create-scada` example
  controls a Create kinetic drive with real speed/load readings, RPM commands and connection/load alarms.
  Its input loop runs independently of telemetry sampling, applies commands before drawing and avoids unchanged redraws.

- The all-addon development build uses Loom directly and enables Configuration Cache and parallel execution,
  allowing independent built-in program artifacts to compile concurrently inside the included Compukters project.

- Addon SDK accepts immutable value-class host responses, nested data/value mixtures and canonical stdlib geometry.
  Value layouts coexist with precompiled interface methods and companion providers.
  Inline record fields avoid Guest record allocations while retaining bounded copying, GC and checkpoint support.
  Sable 2.0 exposes value snapshots with Vector3d, validated Rotation3d and Matrix3d mass tensors.

- Guest stdlib provides immutable `Vector2d`, `Vector3d` and `Vector4d` with Double arithmetic,
  robust normalization and explicit conversions to/from existing vectors. Distinct `Quaternionf`/`Quaterniond`
  preserve quaternion identity and support explicit conversions with the corresponding four-dimensional vectors.
  `Rotation3f`/`Rotation3d` represent spatial rotations through normalized quaternions, with composition,
  inverse, axis-angle and direction-to-direction factories, shortest-arc spherical interpolation, angular distance,
  vector transforms, matrix conversion and read-only quaternion component access through `x`, `y`, `z`, `w`.
  Float rotations use Double intermediates for composition.
  `Matrix3f`, `Matrix3d` and `Matrix4d` provide row-major transforms and bounded native matrix kernels;
  Double variants retain Double precision through multiplication and projection.

- IDE tooling loads canonical platform navigation sources under a separate bounded budget,
  so expanding the standard library does not consume the user project or addon source budgets.

- Guest Kotlin provides reusable `StringBuilder` and `buildString`, full `CharSequence` support for strings, builders and
  user implementations, text iteration, `take`/`drop`
  helpers, padding and repetition, plus signed and unsigned integer parsers with radices 2–36.
  Search, whitespace checks, splitting and sizing accept `CharSequence`; specialized String overloads
  preserve String results.
  Text operations preserve UTF-16 units and respect managed heap quotas; malformed integer text
  returns null or throws `NumberFormatException` according to the selected API.

- Guest Kotlin supports named local functions with direct calls, recursion, nested helpers and escaping
  references. Mutable captures share state with surrounding code and lambdas; direct immutable captures
  stay in typed arguments. Local functions can use expression defaults and appear in class initializers.
  Generic local functions and local helpers inside generic top-level functions specialize concrete calls,
  defaults and escaping captures; primitive arguments remain unboxed within bounded compiler expansion.
  Generic class methods and custom accessors also admit local helpers and callbacks capturing their concrete
  owner, parameters and shared mutable locals; captured owners observe later field updates.
  Class `val`/`var` properties can store, replace and invoke callbacks, including concrete generic holders,
  constructor defaults and custom/inherited accessors. Nullable callbacks can default or reset to null,
  use safe invocation, Elvis and smart casts, and retain separately held aliases after reset. Safe Unit
  calls can produce a retained `Unit?` result. Stored callbacks retain their captures until unreachable.

- Guest Kotlin functions accept expression default arguments using earlier parameters, receivers,
  helpers, lambdas and concrete generic types. Explicit arguments evaluate before omitted defaults;
  inherited defaults preserve overriding method dispatch.
  Function references can omit trailing default parameters; bound receivers are captured once and
  defaults evaluate on every invocation, including inherited method and platform array defaults.

- Guest Kotlin provides `kotlin.random.Random` with reproducible Int/Long seeds, a fixed-seed shared
  default generator, signed and unsigned ranges, floating draws and byte-array filling. Generator
  state survives instruction slices and computer checkpoints.

- The built-in `cmatrix` program draws animated green terminal rain with bright stream heads,
  configurable tick delay and Ctrl+C return to the shell. Its rain uses the reproducible Guest stdlib
  random generator with uniform bounded selection; glyph drawing creates no temporary Guest strings.

- The built-in shell supports cursor editing, Unicode-aware deletion, horizontal scrolling and a bounded
  32-command history that restores unfinished input. `Ctrl+U` clears the line; `Ctrl+C` cancels input or stops
  the foreground command tree and returns to the shell. IDE Run submits a complete command atomically
  at an empty prompt; unfinished input and running editors remain protected.
- Guest `Terminal` provides owned typed Text/Key/SubmittedLine events, named keys and modifiers, standard-library integer
  geometry for cursor/drawing operations, terminal size observations and named palette colors. Output accepts
  `CharSequence` via `toString()`, individual `Char` values and `CharArray` ranges; char/buffer output avoids temporary Guest
  strings, including glyphs in cmatrix and row buffers in edit. The old public
  raw event protocol is replaced; built-in shell, editor and benchmark agent use the new API.

- Gradle Configuration Cache is enabled by default for built-in program generation, Minecraft classes,
  shared tooling assembly, SDK staging, development runs and the global `build` and `verifyLocalFull` task graphs.
  Dependency isolation, archive and license
  checks remain active on replay, including detection of newly added media files. Independent built-in compiler tasks
  can run in parallel within the same project;
  cached build-version selection tracks Git tags on `HEAD`. Minecraft builds use Loom directly for common
  module packaging and development runs, without the Architectury Plugin transformer or Java agent.

- Guest compilation and process launch accept lexical `Path` values and normalize dot components before native
  access. `Compiler.compile` returns a typed result with its own diagnostics and the canonical installed output;
  failed compilation preserves existing executables. Built-in `kotlinc`, shell and boot use these APIs.

- The built-in shell provides `cat`, `mkdir` (including `-p`), `mv` and `rm`, plus command-specific help.
  File commands support quoted names and normalized dot paths. Text output preserves Unicode across bounded
  writes; moves refuse replacement and deletion is limited to files and empty directories. Executable paths
  normalize before foreground launch.

- IDE hover displays short Kotlin signatures with explicit return types, compact documentation entries
  and source information below the documentation. Editor and long hover scrollbars support thumb dragging
  and page clicks alongside mouse-wheel scrolling. Hover documentation and Go to Declaration resolve
  platform callback overloads to their exact attached source, including callbacks with a receiver.
  Unknown references color their complete identifier; errors and warnings underline their exact source ranges.
  Hovering a marked token shows its problems together, preserving source editing and horizontal scrolling.
  Argument hints open when typing a call or pressing Ctrl+P, wrap long signatures and emphasize the current parameter.
  IDE controls share an outlined neutral style, with vertically centered labels in toolbars, dialogs, project navigation
  and status/search bars. Hover and completion popups reserve space around every text row. Floating IDE windows
  share subtle borders and rounded corners; the embedded terminal shares the standalone terminal background
  and IDE window chrome and focus accent. Floating windows also cover native block item icons in underlying controls.
  Attached addon sources have a separate bounded allowance, so bundled library sources do not exhaust it.
  Closed IDE screens ignore late ticks during screen transitions and client shutdown.

- Guest Kotlin specializes `Array<T>` for all twelve non-null primitive families with dense, unboxed
  element storage, including constructors, indexed access, direct loops and concrete generic functions/classes.
  Copies preserve dense storage when types match and box elements when copying into nullable or `Any` arrays.

- Guest Kotlin supports `do-while` loops with body-local variables in the condition, and labeled
  `break@label` / `continue@label` across nested `while`, `do-while`, `Int` progression, primitive and
  reference-array, and supported iterable loops. Loop jumps preserve managed exception and `finally` cleanup across VM
  instruction slices.

- Contributors can create, diagnose and safely complete task worktrees with `worktree-task`: local `dev`/`main` bases,
  readiness diagnostics, integration checks and cleanup with archived evidence, pinned VM and optional addon
  initialization, reuse of available local Git objects and automatic independent Gradle-cache preparation.
  Interrupted setup can resume in a clean checkout without resetting its branch.

- Agent Gradle worktrees initialize independent writable caches from a workspace template using reflinks,
  sharing unchanged disk data while keeping build locks separate. Missing templates bootstrap from idle local
  caches or a successful first build, including after cache deletion. Template refresh preserves existing homes
  and rejects active managed sources; unsupported filesystems never silently allocate full cache copies.

- Guest reference arrays support direct `for` loops and `Array(size) { index -> value }`, including strings,
  objects, nullable elements, reusable vector value classes and generic container elements. Initializers run once per
  ascending index and preserve managed exception behavior.

- Guest paths are pure portable String-backed value classes, constructed with `Path(...)` or the explicitly imported
  `String.toPath()` extension. Paths support `.` / `..` and pure `normalize()`, with ascent clamped at root;
  filesystem operations use canonical virtual targets checked independently by the native boundary.
  A separate `FileSystem` API provides typed metadata/errors,
  binary I/O, strict UTF-8 text, directory snapshots and ordinary create/delete/move operations. Sequential readers pin their file contents;
  writers stage bounded chunks and publish only through a guarded atomic commit. Program exit aborts drafts,
  and hibernation preserves resources and their positions. The old string/status `FileSystem` source facade
  is replaced without compatibility wrappers. Optional separately imported extensions in `compukter.io.path`
  add convenient Path calls without coupling the path type to storage.

- The bundled terminal editor atomically saves its complete buffer and preserves the cursor when saving.
  Pasted text either fits in full, including expanded tabs and automatic indentation, or leaves the document unchanged with a `Buffer full` message.

- The IDE has open-file tabs with retained edits, caret, selection, scrolling and undo history. Tabs show unsaved
  changes and save conflicts, scroll when space is limited, and save modified files before closing. Screen interaction,
  frame composition and reusable controls are shared across both supported Minecraft versions; version adapters handle
  Minecraft input and drawing. Dialog fields support mouse selection, cursor and word navigation, clipboard actions,
  undo/redo and horizontal scrolling; token fields remain masked. Quick file opening (Ctrl+Shift+N) searches filenames
  and paths throughout the project, including collapsed folders, with keyboard and mouse navigation. Active tabs use
  a restrained background and accent underline. The header separates project, file and target; Build and Run use
  labeled controls when space permits. The status bar shows concise activity, with a scrollable read-only Details
  window and complete message copying. Source highlighting, caret, selection and mouse pointing share tab and
  Unicode columns, including horizontally scrolled text; scrolling keeps source text and selection clear of line numbers.
  Opening a project without a computer automatically creates a missing local dependency lock for Kotlin analysis
  and Build, without saving source drafts or replacing existing locks. Deploy and Run require an attached computer.

- Documentation offers a version selector for released 0.5.1 and nightly guides and API references. The released
  documentation opens by default; nightly identifies its current source commit.

- Guest `kotlin.math` provides reusable `Vector2i`, `Vector2f`, `Vector3i` and `Vector3f` value classes,
  Position/Size aliases, arithmetic, dot/cross products, lengths, normalization, interpolation, explicit
  rounding and half-open 2D rectangles. Displays accept vectors and rectangles while retaining scalar
  coordinate calls. Infix `xy`, `by` and `sized` provide compact geometry construction. Immutable `Matrix4f`
  and homogeneous `Vector4f` add reusable 3D transformation composition, radians-based axis rotations,
  perspective/orthographic projection and explicit perspective division with clip-volume checks. Matrix products,
  point/direction transforms and transposition execute as VM intrinsics with scalar Float semantics.

- Guest executables omit unused class and interface methods and their dependencies. Virtual dispatch keeps its
  required implementations; independently compiled libraries retain their exported method surface.

- IDE analysis prepares project snapshots in the background and coalesces rapid edits before preparation.
  Diagnostics, semantic highlighting and completion retain revision checks; existing presentation follows edits
  while new results are pending. Worker cancellation no longer blocks typing on pipe writes.

- Compukters: Sable 1.2 supports peripheral networks between shore devices and moving constructions. Access follows
  real world distance, including rotation and scale; assembly and return preserve bindings, names and complete display
  images. Partial display transfers split into panels with their published tiles and network membership.
  `Physics.massProperties()` exposes immutable merged mass, plot-space center of mass and the full inertia tensor
  for Guest flight-control calculations while retaining the existing pose snapshot API.

- The IDE prepares resolved compiler libraries in the background when opening a project and after dependency or
  target-profile changes. Repeated Guest compilation reuses that library analysis, semantic hashes and export
  indexes within the retained worker,
  including across IDE reopenings. Lowering indexes admitted callable names and signatures instead of repeatedly
  scanning their registries. Final executables are validated and serialized once with compilation limits.
  Changing the dependency selection replaces the preparation; each project
  compilation keeps its own source session and specialization state. Queued preparation yields to Build.

- Host capabilities and addon SDK bindings exchange `ByteArray` directly, preserving arbitrary binary data within
  a 4096-byte call limit. Runtime 0.23.0 / native ABI 23 replaces the previous native boundary; existing VM checkpoints
  cold boot while retaining persistent files.

- Existing Peripheral Cables become Computer Cables: one nonbranching physical line joins two computers, with one
  cable connection per computer. Placement rejects branches, loops and occupied ports without consuming the item.
  Guest programs exchange binary messages through `Network.send(ByteArray)` and `Network.receive()` with no addresses;
  bounded inboxes and catchable interruption handle unavailable peers and broken connections.

- Displays retain RGB graphics and their resolution across program exit, disconnection, chunk unload and server
  restart. Programs draw pixels, lines, rectangles, text and images, with optional coherent frames. Scaled text
  rasterizes the font at the requested size, retaining detailed strokes while preserving text cell spacing.
- Holding the Peripheral Configurator reveals nearby devices, networks and screens with animated outlines hidden
  behind walls. Joined displays have one outer rectangle in every mode, using the network color in Network binding.
  Screen holes, unjoined replacement panels, selected groups and assembly previews are visible, with context and
  action hints beside the crosshair.
- The Peripheral Configurator assembles and expands planar screens up to 8×8 blocks, including holes. Matching
  replacement panels automatically rejoin and restore their image and network; removing the last panel deletes the
  screen. Selecting a screen and Shift-clicking it again splits it into separate panels, preserving their images,
  resolution and network while removing the shared name. Programs select 16×16, 32×32, 64×64
  or 128×128 pixels per block. A screen has one network binding shared by all panels. Assembly inherits a single
  existing network or clears conflicting bindings; any surviving panel keeps the screen reachable. Existing
  `TextDisplay` programs use the same persistent canvas, with their text grid spanning the entire composite screen.

- The addon development stand and standalone Propulsion build use Aeronautics/Simulated 1.3.2, fixing the
  Simulated mixin crash with recent JEI versions.

- Development builds use the standard `-SNAPSHOT` suffix, allowing NeoForge to order them below the matching
  release and accept them within published addon dependency ranges.

## 0.5.1 — 2026-10-10

- Create, Sable and Propulsion integrations have independent repositories and tag-only GitHub release workflows.
  The main workspace pins them as submodules and retains the combined development stand and five-JAR distribution.
- A standalone addon template includes a Guest greeting API, host handler, ABI lock and example IDE project,
  with explicit local-source and downloaded-SDK build modes.
- GitHub Releases include an addon development SDK archive with the Gradle plugin, Guest compiler, platform,
  host APIs and development mod JARs for both supported Minecraft targets. Standalone addon builds can use
  these dependencies without a separate hosted Maven repository.

## 0.5.0 — 2026-10-10

This release expands Guest Kotlin, adds independently installed Create, Sable and Propulsion integrations for
Minecraft 1.21.1, and improves the in-game IDE and computer runtime. Exact language and library boundaries are
listed in [Kotlin support](developers/kotlin-support.md) and [standard library support](developers/stdlib-support.md).

### Release delivery

- Sable integration and the all-addon development stand target Sable 2.0.6 for Minecraft 1.21.1 NeoForge.
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
- Mod publication runs only for pushed release tags, builds both universal mod archives and three addons in
  parallel, and prepares the addon SDK locally before compiling independent addon builds. Automated CI tests
  are temporarily disabled; exact tag, Runtime bundle, archive composition and checksum checks remain enabled.
- Verified autonomous NeoForge releases can be published to GitHub Releases and Modrinth from the same tagged
  artifacts, with component identities, checksums and resumable publication. GitHub Releases also includes the
  Create, Sable and Propulsion addon archives.

### Guest Kotlin

- Guest `HashMap` and `HashSet` support typed and nullable keys/elements, lookup, mutation, growth and
  iteration under ordinary VM heap and instruction budgets. Maps expose live key/value/entry views;
  value-class keys retain nominal value equality, including multi-field layouts. `Pair`/`to`, direct
  `mapOf`/`mutableMapOf`/`setOf`/`mutableSetOf` factories and inline `getOrPut` simplify construction and
  defaults; a runnable inventory-report example demonstrates stock totals and thresholds.
  Structural collection equality and type-argument widening remain outside this subset.

- Compilation reuses prepared libraries for repeated module selections, avoids repeated module hashing during
  linking, and allocates less temporary memory when encoding executable artifacts and library identities. Semantic
  hashing checks debug metadata without encoding it. Live-value analysis uses indexed control flow and bitsets to reduce
  temporary allocations during register allocation and GC-root generation.
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
- Persistent peripheral networks connect computers and devices remotely through a network mode in the Peripheral
  Configurator. Networks and device names survive computer replacement; each computer reaches loaded members in
  the same dimension within a configurable radius of 64 blocks by default. The configurator creates/selects networks,
  binds or removes members, and shows availability. Direct face access remains available. Peripheral Cables remain
  as blocks but no longer provide discovery; existing devices require manual binding. This first stage covers ordinary
  world coordinates; Sable construction integration is a separate follow-up.
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

- Reopening the IDE restores the remembered project over a neutral background, without flashing the project
  selection screen; unavailable projects reveal the catalog for recovery.

- Completion excludes ordinary string content, character literals and comments, while preserving suggestions
  inside shorthand and expression interpolation in quoted and raw strings. Automatic completion waits 50 ms
  after an edit and semantic presentation waits 100 ms, leaving time for analysis and rendering within the
  visible response targets.

- With no project open, a central **Projects** panel groups saved folder paths with create, open and HTTPS clone
  actions; the header menu provides the same project management while editing. Both lists scroll, and projects with missing or invalid manifests remain editable, with analysis unavailable until their configuration is repaired. IDE-owned folders support
  collision-safe rename and confirmed deletion; external directories remain registered across restarts and expose a
  distinct **Remove from list** action that preserves their files. Renaming retains the active file and editor position;
  unsaved buffers prevent folder management until saved.

- New projects start with a runnable Hello World example, with comments explaining the entry point, terminal
  output, dependency resolution and IDE build/run actions. Creating or cloning opens the admitted project directly, independently of
  invalid sibling manifests; catalog errors include the manifest validation detail.

- Analysis cancellation remains acknowledged when a request finishes concurrently, keeping the K2 worker warm
  and subsequent edits incremental instead of waiting for a timeout and reopening the project.

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
  current line and uses contrasting panels, draggable dividers and popup shadows. The header offers a saved Auto / 2 / 3 scale choice,
  independently of the Minecraft GUI scale. Auto uses 2 below QHD (including 1920×1080) and 3 at QHD and above.
  Scale controls remain available when the selected scale is too large for the window.
- Kotlin lexical highlighting colors the `value` modifier, function calls, qualified/use-site annotations and Unicode
  escapes; numeric literals remain separate from range operators and member access while semantic colors take precedence.
- `Alt+F7` opens semantic Find Usages with navigable context. Clickable problems, gutter markers, status counts
  and `F2` / `Shift+F2` navigation use matching source text and reject stale locations. Method declarations show
  non-zero project usage counts that distinguish overloads and remain stable during unrelated edits.
- `Shift+F6` renames supported project symbols across files and unsaved buffers after K2 checks collisions and
  bindings, including infix calls and callable references. Unrelated semantic errors can remain, while new
  errors, reference capture and stale sources reject the entire operation. The change is undoable across
  affected files; syntax errors, library symbols, `main`, operator and inheritance declarations remain outside
  this action.
- Caret placement highlights resolved declarations/references, including interpolation; typing hides semantic
  occurrence marks until the caret is explicitly moved. Text selection highlights literal occurrences, while
  `Ctrl+F` provides case-sensitive search, a counter and Enter/Shift+Enter navigation in writable or read-only files.
- `Ctrl+/` toggles Kotlin/TOML line comments while preserving indentation, selection and line endings in one
  undoable edit. Enter inside an empty lambda expands an indented body and moves the closing brace.
- Completion supports built-in numeric members, receiver-specialized signatures, package context and aligned
  return types, including safe-call and literal receivers. Call insertion reuses delimiters, chooses parentheses
  or a trailing lambda, and places the caret at the first required input; imports and caret placement undo together.
- Identifier matching supports case-insensitive word fragments and CamelCase with prefix ranking and highlighted
  matches. Interpolation suggests visible variables after `$`. Hover shows structured English KDoc with parameter,
  return, exception and other documentation sections, Markdown prose and code examples, and scrolling for long text.
  The editor highlights KDoc tags, subjects, links and code. Documentation comes from project and attached
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
- `Ctrl+C` in a computer or IDE target terminal stops the foreground command and its nested processes/tasks,
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
