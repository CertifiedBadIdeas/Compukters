---
layout: default
title: ABI and representation reference
section: contributors
permalink: /ARCHITECTURE/abi/
---

# ABI and representation reference

[← Architecture](../architecture.md)

This reference groups versioned representation boundaries by the version that introduced them. Those versions are minimum feature requirements, not a claim that older Runtime distributions implement today’s platform. The source owners below define the active contract. Before changing a wire format, inspect each producer, consumer and verifier. Runtime release pins in `build-scripts/src/main/kotlin/RuntimeBundleSupport.kt` describe published bundles separately from the local VM checkout.

## Live named peripheral diagnostics

Analysis protocol **19** appends bounded `PeripheralLookup` facts to presentation replies: source path,
UTF-16 argument range, resolved device type, constant name (256 UTF-8 bytes) and optional-lookup flag.
Facts use the negotiated declaration-location budget and are correlated to the queried source.
`None` diagnostic highlight carries informational observations without an underline. Older analysis
workers fail the existing handshake check. Guest/runtime/artifact contracts remain unchanged.

IDE target payload registration **3** adds the opt-in unavailable-member inspection flag and a bounded
availability enum to named discovery replies. Both Minecraft adapters use the same shared codec;
client/server payload versions must match. Server membership and reachability remain authoritative.

## Addon SDK actuator lifecycle and creative content

SDK 0.6.0 adds `CompuktersLeasedPeripheralEndpoint` and lazy shared-tab item registration. These are additive host
SDK APIs. Existing SDK endpoint implementations and core endpoint constructors retain their binary signatures;
the adapter supplies lifecycle callbacks through a new overload, using the existing core ownership registry.
Discovery never acquires actuator ownership. This changes neither native/Guest capability ABI nor checkpoint
encoding. Addons using the new API require a host containing SDK 0.6.0.

## Explicit construction transfer preparation

SDK 0.6.0 adds `CompuktersPeripheralWorldIntegration.prepareTransfer` for adapters that copy block NBT before
removing a construction. It captures computer host resources before source blocks move, then publishes the existing
checkpoint asynchronously. The filesystem attachment barrier prevents destination execution from racing that
publication. Existing SDK signatures, Guest capabilities and native ABI remain unchanged.

## Minecraft peripheral checkpoint locations

The mod's opaque location payload inside core peripheral resource checkpoint version 2 now has a version byte
`1` and a kind byte. Kind `0` contains three big-endian signed 32-bit computer-relative offsets (14 bytes total);
kind `1` contains a network member UUID as two big-endian 64-bit words (18 bytes total). UUID-backed restoration
uses only the authoritative network directory address in the destination dimension; missing or ambiguous members
produce stale handles. Endpoint identity and process-scoped ownership checks still apply. Multiple logical contracts
at the same member address share that address. Direct devices retain exact-instance validation after translation.
The reader explicitly accepts the previous eight-byte absolute BlockPos payload for older ordinary-world checkpoints;
there is no fallback from a missing UUID to an old address or a same-name device. Unknown versions/kinds, extra bytes
and offset overflow are rejected. This mod-owned encoding requires the new host when writing new checkpoints;
core checkpoint version, Rust VM snapshots, exported C ABI and Guest capability versions are unchanged.

## Scoped Guest line reading

`compukter:core` 9.1.0 adds eager `FileSystem.readLines`, inline scoped `FileSystem.useLines`,
single-pass `FileReader.lineSequence` and explicitly imported Path conveniences. `useLines` and portable
`Closeable.use` advertise compile-time `EXACTLY_ONCE` callback invocation; the existing inline/finally
lowering implements resource cleanup. UTF-8 line decoding, buffering and limits are ordinary Guest code
over existing filesystem capability ABI 2 operations. Native ABI, artifact format, filesystem schemas,
checkpoint format and capability operation count are unchanged. Platform module content identities cover
the added declarations and changed fragments; compile against matching platform/addon identities.

## Cosmonautics space observations

Cosmonautics addon 1.0 introduces its first Guest capability and checked-in operation/record lock.
The SDK generates immutable inline replies for space status, linked planet parameters and Deep Space instance
orbital observations, including canonical `Vector3d` fields. These use existing structured value-response support;
no native ABI, artifact format, checkpoint schema or built-in platform version changes. The handler is stateless
and checkpoints an empty payload. Each request copies current upstream state on the server owner thread.
Applicability failures cross as `UNAVAILABLE`/Guest `IllegalStateException`, rather than NaN-filled records.
The addon README owns coordinate, timing and instance-versus-construction contracts.
`Space.guidance()` appends operation ID 3 in capability version 1; existing operation IDs 0..2 and records are
unchanged. `GuidanceSnapshot` adds dimension/tick/instance/frame identity and three canonical Double unit vectors
from one observation. The bundle content identity changes and dependent Guest projects must resolve/recompile.
No additional frame rotation is introduced: pinned upstream transfers world velocity components directly to the
current orbital frame. Direction normalization rejects degenerate planes explicitly.

## Guest value records

`compukter:core` 9.0.0 represents file metadata, process/compilation results and terminal event payloads as native
Guest value classes. Their property names and host operation contracts remain unchanged; sealed interfaces
box their concrete value payloads with nominal identity. Recompile programs against the updated platform and
addon bundle identities. This changes source value semantics: direct reference equality (`===`) is no longer
valid for these payloads, so the affected public API compatibility lines advance. Crafts & Additions 2.0 and
Propulsion 3.0 make the same representation change for Guest observations while retaining peripheral operation IDs and record field order/types. JVM host records
remain ordinary Kotlin data classes. No native ABI revision is introduced by these source representation changes.

## Linked constant pools

Artifact constants are unique and ordered by their encoded bytes. Reachability pruning can renumber
UTF-16 literal IDs across little-endian byte boundaries, so the linker rebuilds the canonical constant
order after literal relocation and updates every constant reference. The representation and verifier
requirements are unchanged. `LibraryModuleLinkerTest` covers the 256-literal boundary; real Guest editor
execution also exercises this path.

## Terminal scrollback transport (Runtime 0.24.0)

Native C ABI **24** extends full-terminal results with the bounded 512-row history and introduces history
append/replace delta tags 5/6. Both JNI and FFM reject older native ABI versions before decoding. The 49
exported signatures and Guest terminal capability remain unchanged. Full snapshots and compacted deltas
preserve authoritative history; client UI owns navigation, selection and clipboard operations. Logical
checkpoint envelope/schema **5** retains history; older schemas use the existing cold-boot fallback.
See the VM host-neutral terminal wire contract for field widths, order and bounds.

## Guest enum identity

`kotlin:builtins` 1.15.0 makes `Enum.name` and `Enum.ordinal` executable. The compiler-generated Kotlin
runtime library adds a shared abstract nominal Enum base with String/Int fields, using ordinary managed
objects, inheritance and field instructions. Concrete enum types remain distinct. `Enum<E>` and `Enum<*>`
use the common base representation; enum initialization sets both fields before publishing singleton
roots. Runtime imports are appended, preserving earlier import indices. The generated library hash and
platform identity change; dependent libraries rebuild against the updated bundle. Artifact wire formats,
VM instructions, native ABI and checkpoint schema remain unchanged. Existing artifacts retain their
embedded generated runtime library. `testKotlinEnumEntriesVmConformance` checks project and library getters.

## Guest lazy sequences

`stdlib:core` 1.22.0 adds the ordinary Guest `kotlin.sequences.Sequence<T>` interface and
lazy iterable adapters, map/filter/filterNot/take, firstOrNull and toList. Generic source
bodies specialize in consuming artifacts; wrappers, iterators and captured callbacks use
existing managed objects. `stdlib:core` 1.23.0 replaces the filtering cache with scalar field
implementations for all twelve non-null primitives and reference storage for nullable/ordinary
objects. The internal trusted compiler factory shares primitive/reference constructor selection
with list storage; cache operations remain Guest method bodies and clear consumed references.
`stdlib:core` 1.24.0 adds immediate terminal forEach/fold/count/any/all/none bodies using
ordinary iteration, scalar accumulation and inline callbacks. `stdlib:core` 1.25.0 adds lazy
mapNotNull/filterNotNull through mapping and a typed non-null selection cache. Nullable primitive
results keep the existing box boundary; accepted cache values use scalar fields. Dependent bundles rebuild
against the updated core module identity.
The module content identity changes; platform bundle encoding, executable format,
Runtime ABI, host operations and checkpoint schema remain unchanged. Exact semantics and
execution evidence live in [the collection inventory](../../developers/stdlib/collections.md#lazy-sequences).

## Guest enum entries

`kotlin:builtins` 1.14.0 completes the structural Enum comparison declaration for frontend inheritance.
`stdlib:core` 1.21.0 adds sealed `kotlin.enums.EnumEntries<E>` and its private immutable array-backed
implementation. `compukter:core` 8.1.0 exports generated static `entries` getters for its enums. Library
metadata records these generated functions with their canonical typed signatures, including enum-only
libraries; consumers import the same cached collection and singleton entries through ordinary links.
Dependent libraries rebuild against the resulting platform identities.

Compiler-generated private cache holders use existing managed objects, checked reference arrays, static
initialization and generic collection specializations. Each cache initializes after the enum singleton roots.
No instruction, CPKT/CAGB encoding, platform encoding, Runtime/native C ABI or checkpoint schema changes.
`testKotlinEnumEntriesVmConformance` exercises immutable operations and cross-library identity; the
[collection inventory](../../developers/stdlib/collections.md#immutable-enum-entries) owns source semantics.

## Value-class host responses — Runtime ABI 1.20

Asynchronous structured replies admit nominal inline destinations and flattened value boxes, including nested
value/data mixtures. Registered logical record trees match verified physical component layouts; field names,
nominal identity, nullability, widths and reference types remain checked. Existing data-record replies remain supported.
The compiler/linker require ABI 1.20 for inline replies and conservatively for structured replies in artifacts containing
value boxes. Both verifiers reject inline capability destinations claiming an older ABI.

Publication stages at most the bounded expanded record fields, charges each component, roots staged references across
GC and saves partial progress in checkpoints. It copies the complete result into the suspended frame atomically.
Pure inline record fields allocate no Guest record objects; Strings, data fields and explicit reference contexts
retain their managed representations. Guest constructors are not executed by the host, so constrained values use a
raw safe payload followed by a Guest factory. Canonical stdlib geometry requires exact trusted schemas.

Runtime 0.23.1 implements this support, retaining native C ABI 23, CPKT 3.0, CAGB 3, capability schema wire version 2
and response tag 8. Logical checkpoint envelope/schema advances to 4 for pending inline record state; older envelopes
cold boot while retaining published files. Sable addon 2.0 uses Vector3d/Matrix3d and value snapshots, with
Rotation3d created by its validating Guest factory.

Addon metadata archives retain executable library members alongside source value layouts. The compressed
metadata archive remains bounded to 2 MiB and the complete bundle to 4 MiB; expanded metadata has a separate
8 MiB budget, enforced during bounded entry reads and across the archive. Source archives retain their 2 MiB
compressed and expanded limits. This accommodates compiled platform dependencies without removing addon methods;
CAGB encoding and capability schemas remain unchanged. `AddonGuestApiBundleCodecTest` exercises round trips
with expanded metadata above 2 MiB and rejection of either exceeded budget.

## Matrix shape kernels — Runtime ABI 1.19

MatrixMath retains opcode 0x1e/form 0 and original selectors 1–5. Selectors 6–8 add Matrix3f
transpose/matrix/vector products; 9–11 add Matrix3d equivalents; 12–16 add Matrix4d
transpose/matrix/vector/point/direction operations. Three-dimensional matrix layouts contain nine
components and vector results contain three; Double shapes require exact F64 components.
The compiler and linker derive minimum ABI 1.19 only when these new selectors survive. Both verifiers
reject false minimum ABI claims, wrong scalar widths/counts, arity and uninitialized inputs.
Three-dimensional costs are 11/54/18 respectively; 4D Double costs match existing Float kernels.
Execution snapshots operands on the native stack, permits valid destination aliases, performs no managed
allocations and preserves scalar rounding without FMA. Container framing, native C ABI 23, platform
encoding and checkpoint schemas remain unchanged. stdlib:core 1.19.0 supplies the matching declarations.

## Double geometry and quaternion components

`stdlib:core` 1.19.0 adds Vector2d/Vector3d/Vector4d and distinct Quaternionf/Quaterniond value types,
plus explicit vector/quaternion and scalar-width conversions. Rotation3f/Rotation3d contain the corresponding
unit quaternion, use private constructors and validate/normalize through public factories. Axis-angle,
shortest-arc interpolation, angular distance and matrix conversion reuse existing inline/scalar math and
matrix instructions; these source APIs introduce no additional Runtime/native ABI or encoding change. Existing nominal inline layouts and F64
arithmetic/math instructions express this additive source API. Platform module identities change;
CPKT encoding, Runtime ABI, native C ABI and checkpoint schemas remain unchanged. Rebuild dependent
library inputs with the matching platform. The [math inventory](../../developers/stdlib/math.md)
owns numerical semantics and executable evidence.

## Sable mass observations

Sable addon 1.2 appends `sable:sable` capability ABI 1.0 selector 1, `massProperties(): PhysicsMassProperties`.
Selector 0 and the complete existing PhysicsSnapshot record shape remain unchanged. The new record carries UUID text,
dimension, world observation tick, mass, plot-space center of mass and nine named inertia tensor components.
Generated bindings declare two operations and retain the locked historical schemas; old one-operation executables
remain admissible. The Sable module content identity changes, so source consumers rebuild against addon 1.2.
Existing F64 nested-record transport expresses the result: Runtime ABI, native C ABI, worker framing, platform
encoding and checkpoint schemas do not change. The handler remains stateless. See the
[Sable guide](../../players/addons/sable.md#mass-properties) for units, axes, sampling and unavailable-data behavior.

## Guest text helpers

`kotlin:builtins` 1.12.0 and `stdlib:core` 1.17.0 add canonical CharSequence, reusable StringBuilder/buildString,
text iteration, sizing helpers and signed/unsigned integer parsing. The builder uses ordinary managed CharArray
storage. String implements the runtime-owned CharSequence interface directly; CharIterator has one canonical
stdlib owner. Exceptions referenced only by catch parameters are linked from the library.

Runtime ABI 1.18 permits covariant reference results in virtual/interface method dispatch: the implementation
result must be assignable to the declaration, and cannot widen nullability. Scalar results and parameters retain
exact matching. The generated runtime's String.subSequence returns String for a CharSequence declaration;
artifacts containing that canonical interface require 1.18. Module identities and dependent metadata change;
bundle encoding, worker wire format, CPKT instructions, native C ABI and checkpoint schemas remain unchanged.
`stdlib:core` 1.18.0 aligns supported text helper receivers with CharSequence and retains
specialized String-result overloads; no further Runtime or representation change is required.
The [text inventory](../../developers/stdlib/core-text.md) owns signatures, UTF-16 semantics, allocation behavior
and parsing policy.

Attached canonical platform sources use a bounded dependency budget (256 files, 1 MiB per file, 8 MiB total),
independent of request project-source limits. The protocol client and analysis worker enforce the same bounds;
addon sources and user project sources retain their existing requested budgets.

## Guest pseudorandom sources

`stdlib:core` 1.16.0 adds `kotlin.random.Random` and signed/unsigned random extensions.
Seeded sources use ordinary managed objects with wrapping Long state; the managed Default companion
owns a fixed seed-0 source per program/session. Abstract library methods/accessors retain linkable
virtual dispatch slots in platform compilation. Module identities and dependent metadata change;
bundle/module encoding, worker protocol, CPKT instructions, Runtime ABI and native/checkpoint schemas
are unchanged. Floating endpoint correction uses the existing Runtime ABI 1.16 math instructions.
The [random support page](../../developers/stdlib/random.md) owns exact signatures and sequence semantics.

## Typed Guest terminal

`compukter:core` 7.0.0 makes a clean source replacement: owned Text/Key event snapshots, typed keys/actions/modifiers,
standard-library Position2i/Size2i/Rect2i geometry and a typed palette. Primitive external operations live in private
`TerminalBindings`; no public raw event getters or manual event completion remain. Other programs retain canonical
`readln()` input.

Terminal capability ABI 2.1 preserves selectors 0..13 and adds synchronous no-argument I32 observations:
14 cursor X, 15 cursor Y, 16 width, 17 height. New artifacts require minor 1 and declare 18 operations;
the native binding continues admitting ABI 2.0 artifacts. The public facade owns event decoding and always finishes
native event ownership before publishing its immutable snapshot. Standard-library vectors/rectangles retain their
existing value representation; no new CPKT or native transport layout is required.

`compukter:core` 7.1.0 adds owned `TerminalEvent.SubmittedLine` and opt-in
`readEvent(submittedLineLimit: Int)`. Terminal ABI 2.2 adds selector 18, asynchronous `(I32) -> I32`:
the argument is the maximum accepted submitted-line length (zero marks a busy draft); the result
is the existing event kind or 3 for a complete submitted line. Artifacts declare 19 operations;
ABI 2.0/2.1 remain admitted. Existing event-text and finish selectors carry/release the owned line.
The host's existing canonical-line submission resumes the exact opted-in task/request, validates
length, controls and Unicode, and rejects queued/active input. Ordinary raw waits reject submissions;
canonical `readln()` keeps its existing contract. The pending operation/limit and kind-3 continuation
are already represented in session checkpoints; text uses existing active-event storage. Native C ABI,
checkpoint schema and encoding stay unchanged.

`compukter:core` 7.2.0 adds `write`/`writeAt` overloads for Char and selected CharArray ranges;
text overloads accept CharSequence. Terminal ABI 2.3 appends synchronous selectors 19 `(Char) -> Unit`,
20 `(I32,I32,Char) -> Unit`, 21 `(CharArray,I32,I32) -> Unit` and
22 `(I32,I32,CharArray,I32,I32) -> Unit`. Artifacts declare 23 operations; ABI 2.0–2.2 remain admitted.
CharArray ranges are argument-only host schemas whose bounds reference I32 arguments. Native admission
checks the array and bound types; preparation rechecks ranges, copies only selected units into the existing
bounded UTF-16 snapshot and charges ordinary dynamic cost and host code-unit quotas. The snapshot uses
existing String wire values without allocating a managed Guest String. The internal checkpoint host-schema
enum appends tag 10; existing preparation state and snapshot slot encoding are retained and validated on
restore. Native C ABI, executable instructions and Runtime ABI remain unchanged.

Terminal keys C (67) and U (85) extend the admitted wire key set. Ctrl+C replaces Ctrl+T as the reserved foreground
interruption; it reuses status 130 and existing ProcessExited lifecycle events. At an idle prompt the press reaches
Guest code; repeated controls are consumed without killing a subsequent command. Native C ABI 23, Runtime source
0.23.0, checkpoint schema 3 and payload layouts are unchanged. Updated local VM support is required;
published Runtime bundle pins do not establish support for these additions.

## Typed Guest compilation and process paths

`compukter:core` 6.0.0 replaces String-based `Compiler.compile` and `Process.run` with Path-based
facades. Compiler returns `CompilationResult.Success(output: Path)` or `Failed(reason, diagnostics)`;
`Compiler.diagnostics()` is removed. Paths normalize before native calls. Original path lengths remain
bounded at 4096 UTF-16 units, and combined canonical compilation paths must fit the same outbound limit.
Process arguments and its canonical path also share this limit and report `LIMIT_EXCEEDED` before publication.

The new compiler binding emits `compukter:compiler` ABI 2, selector 0: asynchronous
`(String, String) -> String`. Its private reply starts with one status UTF-16 unit (0 success, 1 rejected,
2 stale source/output, 3 invalid compiled artifact, 4 installation I/O failure), followed by diagnostics.
The entire reply fits the inbound text limit; diagnostic truncation preserves Unicode scalar boundaries.
Status and diagnostics belong to the same request, including across cooperative tasks and checkpoint restoration.
ABI 1 remains admitted for historical executables; new source has no compatibility wrappers.

Native C ABI 23, Runtime source version 0.23.0, CPKT 3.0, checkpoint envelope/schema 3 and the compiler worker
protocol remain unchanged. Existing String transport carries the reply, and the pending admitted operation
already retains its result representation in checkpoints. ABI 2 requires the updated local VM; published
Runtime pins do not imply support for this new capability. Library consumers rebuild against the changed
core module identity. Imported value-class fields are loaded through their nominal storage box before unboxing.

## Portable Guest filesystem

`compukter:core` 5.1.0 permits dot components in lexical Path values and adds pure normalization,
with ascent above root clamped at root. FileSystem sends canonical paths; Rust retains its independent
canonical-path parser and component-based rights checks. Original Guest inputs retain the 4096-UTF-16-unit
transport bound; native limits apply to the normalized target. No capability, platform encoding or Runtime
ABI changes are required.

Version 5.0.0 exports Path as a String-backed inline value class instead of a managed data class.
Nullable/Any/collection boundaries use its canonical nominal box. The representation change and removal
of generated `copy()` / `component1()` members require dependent platform/addon source libraries to rebuild.
Existing standalone executables remain admitted; filesystem capability ABI 2, native ABI 23 and
platform encoding are unchanged.

Version 4.1.0 adds the pure `String.toPath()` extension in `compukter.filesystem`. This additive
source API change leaves platform encoding, filesystem capability ABI and Runtime ABI unchanged.
Version 4.0.0 replaces the old string/status FileSystem source facade with Compukters-owned Path,
pure lexical paths and an explicit FileSystem facade with metadata, typed exceptions, immutable
read/directory snapshots and guarded atomic replacement drafts. Path has no storage members or native
construction request; optional compukter.io.path extensions delegate to FileSystem. Native limits are
checked when operating on storage.
New source emits `compukter:filesystem` ABI 2 with 15 synchronous operations; historical ABI 1 remains
admitted for existing artifacts without source wrappers. Private byte replies begin with an unsigned status
byte (0 success, otherwise the existing filesystem error magnitude), with at most 4095 content bytes.
Metadata adds kind, executable flag, little-endian u64 size and u64 revision. Directory replies contain one
status UTF-16 unit followed by one sorted name; a status-only success means EOF. Move paths are sent in
separate requests through a guarded source resource. See `src/computer_filesystem.rs` in the VM and the
canonical `FileSystemBindings` registry for the exact operation schema.

Process-owned resources preserve IDs, snapshot positions, pinned and draft bytes, revision guards and
poisoned-write state through logical checkpoint envelope/schema 3. This requires native ABI 23 and
Runtime 0.23.0; ABI 22 checkpoints cold boot while preserving published files. Native value tags, the
49-export transport inventory, executable container 3 and platform codecs are unchanged. Runtime source
consistency is verified locally; tagged release packaging still requires matching published bundles.

## ByteArray host values

Native C ABI 22 adds direct ByteArray arguments and results to host capabilities. Kotlin `BYTE_ARRAY` and Rust
`ByteArray` use wire tag 9. Requests and encoded responses contain a little-endian u32 length followed by raw bytes.
The combined byte-array arguments per request and each response are bounded to 4096 bytes. VM admission requires a
non-null signed I8 array; other primitive arrays and nullable references are rejected. Record fields cannot contain
ByteArray values. The addon SDK signature parser and generated host contracts accept ByteArray directly.

Requests and responses copy byte contents before returning control to their producer. Guest result allocation and
outbound copying remain sliceable and charged, and checkpoint state preserves partial allocation and pending data.
This changes the native checkpoint contract and Runtime identity; native ABI 21 checkpoints require a cold boot,
while the filesystem remains persistent. The selected candidate is Runtime 0.23.0; tagged packaging requires its
published bundles. Executable container format 3 and existing Guest array representation remain unchanged.

## Diagnostic highlighting

Analysis protocol 16 appends a bounded little-endian u16 highlight enum to each diagnostic: standard (0) or unresolved
reference (1). K2 factory identities and compiler diagnostic codes produce this classification; the client
never parses localized message text. Existing severity, path and exact UTF-16 range admission remain unchanged.
Protocol 15 frames and unknown enum values are rejected. Worker payload identities change with the coherent
producer/consumer update; platform, compiler framing, artifacts and native ABI remain unchanged.

Analysis protocol 17 adds an optional UTF-8 active-parameter documentation string after each signature candidate's
best-candidate flag. Both producer and consumer enforce the negotiated detail-text byte bound, and documentation
requires a valid active-parameter span. The existing KDoc parser selects `@param` by semantic parameter name;
English UI labels are separate from the body and do not introduce a localization identifier ABI. Protocol 16
workers are rejected at handshake. Compiler, Guest platform, artifact and native contracts are unchanged.

Analysis protocol 18 adds the explicit `ImportFixes` cursor query and its bounded result: an optional reference
range and distinct qualified import names, each with one insertion edit. Candidate count uses the negotiated
completion-item bound; names and edit text use the detail-text byte bound. The worker checks each candidate through
a restored K2 preview and considers at most 16 previews per request. Existing resolved bindings and errors must
remain valid. The result carries source UTF-16 coordinates, including conversion from normalized PSI for CRLF.
Protocol 17 workers are rejected at handshake; compiler, platform, artifact and native contracts are unchanged.

## Boundary identities

| Boundary | Current representation | Owner |
| --- | --- | --- |
| Executable container | Format 3; covariant method results require Runtime ABI 1.18 | `modules/common/compiler-artifact` and `host/compukter-vm/src/artifact/format.rs` |
| Native session transport | C ABI 24, checked by both FFM and JNI | `host/compukter-vm/ffi/src/lib.rs` and `modules/common/native-runtime` |
| Base platform | Bundle format 9, standalone module format 5, platform ABI 3 | `PlatformBundleCodec` in `modules/common/platform-bundle` |
| Kotlin metadata carrier | Private format 6 | `platform-k2` |
| Compiler worker | Protocol 5; explicit source-free library preparation | `compiler-client` and `compiler-k2` |
| IDE analysis | Protocol 19 | `ide-analysis-client` and `ide-analysis-k2` |

## Reference-array loop surface

`kotlin:builtins` 1.11.0 adds the structural external `Array<T>.iterator(): Iterator<T>` declaration used by
K2 to resolve direct array loops. Compiler lowering consumes the canonical loop shape and emits existing
array-length, indexed-load and scalar advancement instructions, with the same element representation as
indexed reads. It does not provide executable stored array iterators. This is an additive source surface;
dependent libraries rebuild against the new module content identity. Platform ABI 3, bundle format 9,
standalone module format 5, CPKT 3.0 and native C ABI 23 are unchanged.

## Reference-array initializer surface

`kotlin:builtins` 1.10.0 adds the public `Array<T>(Int, (Int) -> T)` constructor for the currently admitted
reference-array element layouts. Compiler lowering reuses allocation, callback dispatch and array stores;
no new instruction or representation is introduced. Dependent platform modules rebuild against the new
module content identity. Platform ABI 3, bundle format 9, standalone module format 5, CPKT 3.0 and native
C ABI 22 remain unchanged.

## Executable method retention and minimum ABI

Final executable linking may remove unused class/interface methods and their dependencies. Library assembly retains
complete exported method surfaces. Native admission still requires implementations for every retained virtual or
interface-owned declaration; the linker closes over conservative name/parameter-count candidates on reachable types
and rebuilds contiguous method ranges. This changes neither CPKT 3.0 nor native C ABI 22.

The artifact's minimum semantic Runtime ABI reflects remaining instructions and type layouts, rather than the full
source library surface. Array and exception root superclass methods require ABI 1.10 even when no remaining
instruction implies that version. Removing an unused hash body can lower a requirement without removing diagnostics
or introducing a new encoding. Reader capabilities for compact debug/root sections remain separate requirements.

## Compiler worker preparation

Compiler protocol 5 adds `LIBRARY_PREPARATION_REQUEST` (wire tag 6), `LIBRARIES_PREPARED` (tag 7) and the
`LIBRARY_PREPARATION` feature bit. The request contains request ID, pinned worker identity, limits, ordered selected
module identities and immutable addon payloads, with no project sources or target artifact. Preparation validates
selection and fills the same single-entry library frontend cache used by compilation. The acknowledgement carries
request ID and resource/timing metrics; it never contains a `.cpkt`. Platform failures remain valid terminal results
for either operation. A mismatched result kind or request ID invalidates the client worker.

Protocol 4 frames are rejected without a fallback. Worker payload hashes change with the new producer and consumer;
platform, artifact, codegen and native ABI contracts are unchanged. Both operations share bounded framing, admission,
queue capacity and deadlines. Queued preparation yields to explicit compilation; an active preparation completes
before the next request. No project FIR or IR enters the retained library cache.

## Guest matrix module identity

`stdlib:core` 1.15.0 provides immutable `Matrix4f` and homogeneous `Vector4f` in `kotlin.math`.
Matrix multiplication, vector multiplication, point/direction transforms and transposition use trusted
private primitives lowered to verified Runtime ABI 1.17 kernels. Factories, indexing, perspective division,
equality, hashing and boxing retain ordinary compiled library bodies. Component constructors have explicit
arguments; the current metadata default-argument schema does not encode Float literals.
Dependent libraries rebuild against the changed module content identity. Bundle format 9, module format 5,
platform ABI 3, metadata format 6, executable format 3 and native ABI 23 remain unchanged.
The [math guide]({{ '/STDLIB-SUPPORT/math/#matrices-and-homogeneous-coordinates' | relative_url }}) owns
coordinate conventions and numerical contracts; `testKotlinGeometryVmConformance` exercises the public API.

## Guest geometry module identity

`stdlib:core` 1.13.0 adds reusable 2D/3D Int/Float value-class vectors, Position/Size aliases and 2D rectangles.
Source metadata retains alias spellings; the library loader expands admitted aliases when matching canonical
compiled signatures, preserving the underlying nominal type and default arguments. Value-class initializer
literals participate in the artifact constant pool. Bundle format 9, module format 5, platform ABI 3,
metadata format 6 and native ABI 22 remain unchanged by geometry. `compukter:core` 2.3.0 adds typed display overloads
and a vector size property, delegating the same scalar display capability operations. Dependent libraries resolve the matching module content
identities; geometry uses existing value-class and math execution instructions.

## Guest hash collection module identities

Guest hash collections introduce public Set/Map contracts in `kotlin:builtins` 1.9.0 and ordinary source
implementations, Pair and populated factories in `stdlib:core` 1.11.0. Their module content identities change; bundle format 9, module
format 5, platform ABI 3 and Runtime ABI 1.15 remain unchanged by hash collections. The current native C ABI is 23 for checkpoint transport. Programs and dependent
platform/addon inputs must resolve the matching module identities. Hashing, generic equality, managed
objects and specialization reuse existing instructions and representation rules.
## Remote redstone and peripheral ownership

`compukter:core` 7.4.0 adds typed `RedstonePort` discovery and six-face operations using the additive
`compukters:redstone-port` capability ABI 1.0. Selectors 0–5 are asynchronous input read, encoded weak/direct
output, edge wait, exact/threshold wait, handle close and atomic all-face output. Inputs and outputs use I32; levels are bounded to 0–15,
face indices to 0–5 and encoded output to 0–31. The port's common host maintains at most 64 pending waits per
process. Resource checkpoint format 1 stores only request identities and restores interrupted waits as I/O failures.
No output NBT or persistent port instance descriptor is provided. Bundle/artifact encodings and C ABI 23 remain
unchanged; programs and dependent bundles rebuild against the changed core module identity.

Common typed acquisition reserves an exact endpoint identity for one running process. Other processes receive
an unavailable host failure before reads or writes, including competing processes on the same computer and alternate
contracts of the same device. Discovery snapshots reserve no unread devices. Process stop/reset, explicit final
handle close and disconnected endpoint expiry release ownership. Peripheral checkpoint format 2 and the published
addon contract JVM constructors remain unchanged; persisted handles must reacquire available exact instances.

## Configurator structure geometry

Configurator overlay payload registration version 2 appends optional inclusive minimum/maximum block positions
for each device. Bounds contain the canonical anchor, span 1–64 blocks per axis and are validated on decode.
Single-block devices omit bounds. Both producer and consumer change together; version 1 overlay peers are incompatible.
The additive `CompuktersPeripheralGeometryProvider` interface leaves existing provider SAMs, device constructors,
Guest capabilities, exact-instance stamps and persistent network format 1 unchanged. Structure geometry is presentation
metadata and is excluded from canonical device identity. See [world integration](world-integration.md#peripheral-structure-geometry).

## Computer network capability

`compukter:core` 2.2.0 adds `compukters:network` ABI 1.0, with three asynchronous operations: 0 returns connection status,
1 accepts a `ByteArray` and returns Unit, and 2 waits for and returns one `ByteArray`. The host keeps waiting request IDs
in each program scope; the physical computer owns its shared volatile inbox. Mailboxes hold 16 messages per endpoint,
with a 4096-byte message limit and a 1 MiB world queued-byte cap. The loaded-world adapter bounds active derived
connections to 256 and indexed cable/endpoint positions to 65,536. It never forces chunks.

The dependency index retires only affected connections on cable/computer mutation and participating chunk events;
ordinary neighbor updates do not retire a link. Exact block-entity identity and VM epoch prevent replacement or reboot
from inheriting queued data. A repaired cable creates a new generation. Resource checkpoint version 1 stores waiting
receive identities only; restoration completes them with input/output failure because inboxes are transient.

## Guest math module identity

`stdlib:core` 1.12.0 adds the portable `kotlin.math` surface. Floating primitives require Runtime ABI 1.16;
integer and derived wrappers use ordinary Guest code. Canonical source getters retain extension-receiver
identity, including the distinct Int and Long `sign` getters with the same Int result. Platform bundle 9,
standalone module 5 and private metadata carrier 6 remain unchanged; rebuild dependent module inputs.

## Execution checkpoint development

The VM has a logical computer checkpoint envelope (format 5), owned by
`host/compukter-vm/src/checkpoint/envelope.rs`. Its version, native runtime/schema identity, computer ID,
filesystem generation, payload lengths and SHA-256 cover the execution and host descriptor bytes together.
Runtime identity hashes the logical schema, a zero byte and the little-endian u32 native C ABI. Runtime
revisions do not participate in admission: `0.<abi>.<revision>` is one compatibility family, including
checkpoint restore in both directions, filesystem state, executable admission and native calls. Incompatible
contract or codec changes require an ABI increment; a revision must preserve those contracts. The earlier
unpublished development identity based on the full version is rejected without a fallback. CI exchanges a
full-computer checkpoint between two real builds of adjacent revisions in both directions.
FFM/JNI admission checks native ABI 24 without a package-revision constraint. Release selection is
separate: `config/runtime-release.properties` selects one compatible published revision and its exact
release commit, independently of the development VM submodule. Alternate descriptors may select any
`0.24.x`; packaging still verifies the selected archive identity, formats and hashes. Both mod archives
and the release inventory record the actual selected native component for future external delivery.
Production version preparation queries the latest complete stable GitHub Release. Revision and ABI requests
are idempotent within a release cycle; an ABI request updates the native constant and version together.
After successful publication the workflow prepares the next revision on `main`. An explicit ABI request
marks incompatible development; semantic breaking changes are not inferred from arbitrary source diffs.
Rust exposes contextual capture/restore and bounded atomic store methods through C ABI 21, JNI and FFM. Minecraft carriers use this boundary for unload and orderly shutdown; failed restoration cold boots from ROM while retaining ComputerId and `/home`.
The native reference is `host/compukter-vm/docs/architecture/computer-checkpoints.md`.

The core host descriptor uses little-endian version 1 (`CPTH`, u32 version), a checked input-wait flag,
cumulative granted Guest/maintenance counters and saturation flag, confirmed redstone output, live process
IDs (root 0, at most 32), and at most 256 timers. Each timer carries task/request identity, process ID,
original duration and remaining ticks. A length-prefixed addon resource payload is bounded to 1 MiB.
Decode rejects invalid flags/counts, duplicate timer identities, unknown process scopes, invalid durations,
truncation and trailing bytes. These bytes share the native envelope integrity check; they never mirror VM state.
Restore parks execution until resource rebinding and durable consumption finish, then recreates timer deadlines
relative to the activation tick. Cumulative diagnostics survive, while per-tick CPU grants and old compiler
epochs do not. Actor close drains accepted completions before capture. Undelivered external requests receive a catchable
unavailable result, so restoration never replays a world mutation. Minecraft adapters use the close barrier
on chunk unload and before the actor/store shutdown sequence. Both Minecraft version families run a real
GameTest that continues an unsaved Guest editor through carrier replacement.

Addon resource checkpoint framing version 1 carries bounded length-prefixed parts (at most 128 parts and
1 MiB total). Generated host bindings delegate capture and restore to each handler; a handler must explicitly
implement capture, including a zero-byte payload for stateless handlers. Unsupported capture fails instead of
assuming a stateful addon is stateless. Process-scoped hosts retain a high-water process ID and independently
restore resource descriptions for live scopes; retired scopes are dropped and newly entered scopes remain lazy.

Peripheral resource format 2 preserves handle and discovery token high-water marks, ordered discovery
snapshots and contract/location/persistent-instance descriptors. Exact matching instances may rebind after
loading; missing, replaced or unidentifiable instances produce stale handles. A physical address alone never
rebinds an old handle to a replacement. Panel identities live in block-entity persistence and canvas identities in dimension SavedData. Display resource
format 4 restores writer leases and bounded private frames (including owning task and cumulative work) after
peripheral admission, without replaying published pixels or overwriting another active writer. Readers accept
formats 2 and 3; legacy format-2 rows are consumed but never replayed. `compukters:display` 1.2 preserves
operations 0–16 and adds bounded click polling at operation 17. `compukter:core` 7.3.0 exposes the typed input
facade and reusable drawing widgets; the separate network capability retains its selectors.

The Minecraft addon SDK optionally accepts `CompuktersPersistentPeripheralEndpoint`, extending the existing
endpoint interface with a persisted exact-device stamp (at most 128 UTF-8 bytes). The base adapter forwards
this stamp to peripheral resource format 2. Existing endpoint implementations remain unchanged and restore
as stale when they provide no persistent identity. The extension adds no Guest operation, native ABI or
checkpoint framing version.

Propulsion resource format 1 uses little-endian u32 version 1 followed by ordinary and vector command lists.
Each list has a u32 count; the combined maximum is 1024 unique positive peripheral handles. Each entry stores
handle, checked 0/1 peripheral-mode flag, IEEE Float digital-input bits and ordinary thrust percentage (1..100,
zero for vector engines), a checked steering flag with optional Float X/Y bits, and a checked thrust flag with
optional Float raw upstream output bits. Digital input is finite 0..1, steering is finite -1..1 and thrust is
finite nonnegative; ordinary entries cannot carry vector overrides. Decode rejects duplicate handles, invalid
versions/flags/counts/ranges, truncation and trailing bytes before applying world effects. Restore also checks
current configured thrust limits. UUIDs live in engine full NBT as `compukters_propulsion:identity`; resource
commands live only in the computer checkpoint. Unused Create handlers encode empty state and reject nonempty
restoration. Native and peripheral formats are unchanged.

## Native Runtime bundles

Native Runtime platform bundles use manifest schema 2 and contain the FFI and JNI native libraries for one
operating-system target under a single Runtime, VM commit, and C ABI identity. Bundle validation covers both transport
entries before staging; the Java 25 runtime module packages only FFI, while the Java 21 runtime module packages only
JNI. The two Minecraft release artifacts therefore share one pinned native release without carrying an unusable
transport. FFI ABI 15 carries host failures as a stable category plus a non-empty, bounded UTF-8 detail instead of an
opaque numeric producer code; the VM retains that detail without allocating while resuming and exposes it in terminal
process diagnostics.

## Source diagnostics and native results

Normal compiler output carries instruction DEBUG boundaries with original source provenance, including across
inline normalization and linked libraries. Optional module section `DEBUG_SOURCE_POSITIONS` (`0x8001`, flags zero)
uses the standard indexed envelope; every record is exactly three little-endian `u32` fields: DEBUG record index,
one-based source line, and one-based UTF-16 column. Indices strictly increase and must address the module's DEBUG
table; line and column must be positive. Both debug sections count against metadata limits and are excluded from
module semantic hashes. Source positions alone do not change DEBUG encoding or executable/runtime ABI. Older Rust readers skip this
optional extension; older Kotlin artifact readers may require an update. Old artifacts use source path plus UTF-16
offset when present, otherwise function and bytecode coordinates. Runtime formatting never reads current source files.

Source builds after [#704](https://github.com/CertifiedBadIdeas/Compukters/issues/704) may also emit module section
`DEBUG_PATHS` (`0x0111`, flags `CRITICAL = 1`, `SEMANTIC = 0`). Its standard indexed records contain unique,
canonical relative UTF-8 source paths, ordered by first use in DEBUG; the table is nonempty, requires DEBUG, and
has no unused entries. Canonical paths are nonempty, have no leading slash or backslash, and contain no empty,
`.` or `..` slash-delimited segments.

Presence of DEBUG_PATHS selects compact DEBUG: each record is exactly seven little-endian `u32` fields
(function, block, instruction, start UTF-16 offset, end UTF-16 offset, inline parent, path pool index). The first
six fields and DEBUG_SOURCE_POSITIONS are unchanged. Without DEBUG_PATHS the last field remains the inline UTF-8
byte length followed by those path bytes. Pool IDs must be in range; inline parents must precede their child.
All three sections count toward debug limits and are excluded from semantic hashes. Rust retains shared ranges
into admitted artifact bytes; it does not expand repeated paths. The writer uses the pool only when aligned
payload and directory costs decrease the physical artifact size.

Compact debug paths retain container format 3.0 and semantic Runtime ABI 1.15; the current native C ABI is 23 for checkpoint transport. New readers accept
legacy artifacts; readers without DEBUG_PATHS support reject its critical section. This is a reader capability
requirement independent of semantic ABI. The development VM uses Runtime 0.23.0 with native C ABI 23, which includes
this reader support. Release packaging admits the published bundles only when their version, ABI, formats,
checksums and VM commit match the pin; a source-built native alone does not establish release compatibility.

Native C ABI 17 appends a length-prefixed, bounded UTF-8 trace to terminal outcome tags 2 (OOM), 5 (Guest trap), and
6 (VM fault), after their existing scalar payload. Empty text means unavailable diagnostic text. FFM and JNI validate
ABI 23 before decoding; both retain typed failures and carry the trace through the runtime host. Other wire tags and
guest capability schemas are unchanged.
Native C ABI 19 adds `compukter_resume_value(handle, taskId, requestId, payload, payloadLength)`.
The caller owns the byte buffer; native code validates and copies its contents before returning and retains no caller
pointer. The payload starts with version 1 and a HostValueType tag, followed by exact little-endian scalar data:
I32/F32 use four bytes, I64/F64 eight, Bool one checked byte, Char one UTF-16 code unit, and String a u16 code-unit
count followed by exact UTF-16. Unit has no payload. The boundary accepts at most 64 KiB and 4096 String code units;
unknown versions/tags, invalid widths/Boolean values, over-limit strings and trailing bytes fail before consuming the
pending request. Existing scalar resume exports remain available. The SDK can now generate Long, Double and Char
completion handlers, and both JNI and FFM dispatch those responses through this encoding.

## Compact safepoint root ranges

Source builds after [#706](https://github.com/CertifiedBadIdeas/Compukters/issues/706) may emit module section
`SAFEPOINT_ROOT_RANGES` (`0x0112`, flags `CRITICAL = 1`, `SEMANTIC = 0`, element count 1). Its raw 16-byte payload is
encoding version (`u32`, currently 1), expanded root record count (`u32`) and canonical expanded indexed payload
length (`u64`), all little-endian. It requires the module's SAFEPOINT_ROOTS section.

With this marker, each indexed SAFEPOINT_ROOTS record contains function, block, first instruction boundary and
positive run count (`u32` each), reference count and zero reserved (`u16` each), followed by sorted, unique
(value ID, physical component ID) `u16` pairs. A range spans consecutive boundaries within one block. Ranges
are ordered and cannot overlap; adjacent ranges with identical references in the same function/block must be merged.
Without the marker, retain the existing 16-byte root header and per-boundary records. The writer selects ranges
only when their aligned payload plus the marker and directory entry is smaller.

Readers reconstruct every original boundary before ordinary verification and execution-image admission. Owners,
block endpoints, arithmetic, reference counts, expanded row counts, per-function safepoint limits and cumulative
expanded bytes are checked before expanding ranges. The expanded canonical root payloads across all modules,
including legacy modules, count against the artifact-byte policy. Kotlin's `ArtifactReader.read(bytes)` defaults
to a 16 MiB cumulative expansion budget; `read(bytes, maximumExpandedRootBytes)` allows a caller-selected budget.
The expanded marker count is capped at one million in Kotlin and `records_per_section` in Rust. Rust uses fallible
allocation and checks reference ordering before expansion. Ranges do not remove GC checkpoints or change root semantics.

Module semantic identity remains the canonical **expanded legacy** encoding: for range-encoded SAFEPOINT_ROOTS,
the native verifier streams the original indexed envelope, offsets, padding and per-boundary records into the
existing module hash. Other semantic sections retain raw-payload hashing; the range marker is excluded. Imports,
precompiled module identities and source diagnostics therefore keep their hashes. Container 3.0, semantic
Runtime ABI 1.15 remain unchanged by root ranges; the current native C ABI is 23 for checkpoint transport. Readers predating the marker reject it as unknown critical.
The mod's Runtime 0.23.0 candidate includes this reader capability; the release gate validates the published bundles
against their exact pinned VM commit before packaging.


## Runtime ABI 1.3

Runtime ABI 1.3 adds exact decimal materialization for the existing `I64` scalar form; artifacts require it only when
an `I64` value is converted to `String`, while purely numeric `Long` programs remain compatible with Runtime ABI 1.0.

## Runtime ABI 1.4

Runtime ABI 1.4 adds Kotlin-compatible decimal materialization for F32 using Kotlin/JVM-compatible spellings, including signed zero, infinities, NaN,
and the smallest subnormal values; purely numeric `Float` programs likewise remain compatible with Runtime ABI 1.0.

## Runtime ABI 1.5

Runtime ABI 1.5 adds `ArrayCopy` (opcode `0x3b`, form 0), gated by semantic feature bit 5. Its five little-endian u16
register operands are source array, destination array, source start, destination start and element count. Verification
requires assignable element representations and Int range operands; runtime checks all ranges before any write.
The fixed instruction cost is 2 and dynamic cost is 1 per copied element. Native memmove-style chunks contain at most
256 elements and respect the remaining Guest budget, preserving overlap direction across slices. Pending operands
remain managed roots and the instruction retires only once copying finishes. Public `copyOf` allocates and zeroes a
fresh array through existing budgeted allocation, then uses `ArrayCopy`; `copyInto` allocates no temporary buffer.
At introduction, this feature retained C ABI 17. Old executable artifacts remain accepted; older VMs reject artifacts requiring ABI 1.5.
The K2 compiler infers its minimum runtime ABI again after final linking and specialization reuse, from retained
instructions. General artifact linking preserves an explicitly declared minimum unless this inference is requested.

## Runtime ABI 1.6

Runtime ABI 1.6 admits distinct nominal operand types for the existing `RefEqual` and `RefNotEqual` instructions.
Both operands must still be references and the result Bool. Comparison only examines reference identity, including
null; it does not dereference values or coerce them to `Any`. Same-nominal comparisons remain valid under older ABI.
Instruction encodings, fixed costs, semantic feature bits and C ABI 17 are unchanged. K2 lowers `===`/`!==` directly,
and producer/linker inference conservatively requires ABI 1.6 when operand type references differ. This does not add
general array covariance or array-to-`Any` conversions.

## Runtime ABI 1.7

Runtime ABI 1.7 adds an explicit optional superclass to nominal array records. Array header flag bit 0 indicates
a non-null TypeRef appended after the element ValueType; flag-zero records remain unchanged. The parent must resolve
to a non-abstract, non-final, zero-arity root class with no superclass, interfaces, fields, methods or initializer.
The compiler emits the existing `kotlin.Any` identity, and linkers preserve and relocate this edge. JVM validation,
Rust verification and runtime assignability follow the same explicit relationship; no name-based universal type
rule is introduced. Arrays assigned to `Any` or `Any?` retain their managed reference and dynamic type without
boxing or copying. Reverse casts and type tests inspect that dynamic type. Legacy arrays without a parent remain
accepted with their previous assignability behavior; newly compiled arrays require ABI 1.7. Precompiled Guest
libraries containing legacy array types need rebuilding to expose the new array-to-Any relationship. The native C ABI and
semantic feature bits are unchanged. Function types and array covariance are not expanded by this contract.

## Runtime ABI 1.8

Runtime ABI 1.8 reserves class header flag bit 2 for one explicit Throwable root per artifact. This open,
non-generic class has two instance fields in declared order: nullable `kotlin.String` message and nullable
self-typed cause. It has no methods, interfaces or initializer and may inherit only a stateless root class.
JVM and Rust validation reject invalid payloads, duplicate roots and role metadata below ABI 1.8. Exception class
identity is not inferred from its name. `Throw` and handler tables require this verified root and ABI 1.8;
legacy exception artifacts must be rebuilt, with no trap fallback. Admission resolves handlers once, innermost
region first and then in source order. Handler inspection and frame unwinding are budgeted. Catch receives the
original managed reference; pending exceptions and failed-task slots remain GC roots. A failed child task does not
terminate its siblings: each join rethrows the retained exception at that join site. An uncaught root exception
terminates the process. Native C ABI 18 adds outcome tag 12 containing a length-prefixed bounded UTF-8 diagnostic
(class, message, at most four causes and 32 stack frames); no Guest pointer crosses the boundary. Kotlin transports
expose `VmOutcome.UncaughtException`. K2 lowers typed `try/catch` statements and expressions to the same handler
tables, preserving source catch order and result registers. `finally` uses out-of-line cleanup blocks outside
the protected range being exited: normal exits share cleanup, exceptional exits use a catch-all, and deferred
return/break/continue paths run inner-to-outer cleanup before their target. Return values are snapshotted before
cleanup; a cleanup return or throw replaces the pending exit. Local loop/inline-block exits do not run cleanup
for enclosing scopes they remain inside. Lowering shares the artifact's conservative exceptional-edge predicate
with verification and liveness, omitting handlers for protected code that cannot throw. Native verifier
dataflow owns reachability across both ordinary and exceptional edges; a catch-only continuation is valid.
Ordinary Guest precondition functions use the same mechanism: `require` throws IllegalArgumentException;
`check` and `error` throw IllegalStateException. Lazy `() -> Any` messages execute once on failure and convert through virtual `toString`; conversion exceptions propagate. Synthesized
exhaustive-when branches allocate and throw NoWhenBranchMatchedException, including for reference-valued results;
no fake result or trap fallback is emitted.

## Runtime ABI 1.9

Runtime ABI 1.9 adds a runtime-exception role tag in class header bits 3..7: 0 is ordinary;
1 arithmetic, 2 index bounds, 3 negative array size, 4 null pointer, 5 class cast,
6 illegal argument, 7 illegal state, and 8 I/O. Tags 9..31 are invalid. Each role may occur at most
once per artifact and must name a non-abstract, non-generic subclass of the verified Throwable root.
The subclass and its intermediate ancestors have no own fields, methods, interfaces or initializer;
only the root supplies message/cause storage. Only `Throwable` has trusted external payload constructors.
Standard descendants in `stdlib:core` and `compukter.io.IOException` in `compukter:core` use ordinary Kotlin
constructors and real superclass calls. Their constructor functions receive the canonical runtime class;
library fragment assembly retains that class's single exported owner rather than declaring another nominal type.
Role identity is preserved through library linking and
specialization fingerprints; linking infers ABI 1.9 from retained role metadata. Fallible integer arithmetic,
array allocation/access/copy, string ranges, reference access/cast, channel and host capability operations retain their factory
roles as implicit dependencies even without a source catch. Both validators reject these operations below
ABI 1.9 or without the required roles, with a rebuild requirement; floating division remains nonthrowing.
The VM materializes the bounded error message through budgeted compact-string construction, then allocates and initializes
the ordinary managed exception through existing reservation/zeroing/collection machinery. Pending message
and exception references are GC roots and pending storage is quota-accounted. Allocation failure remains
noncatchable OOM. Handler lookup uses the original failing instruction; no terminal-trap path is retained for
these operation errors. Invalid channel arguments are catchable IllegalArgumentException; exhausting channel
storage remains a noncatchable resource fault, as do stack overflow and other VM faults.
Host EOF/I/O failures raise IOException; unavailable/other failures raise IllegalStateException. Cancellation
remains terminal. Accepted failures retain bounded owned details per waiting task and materialize only after
that task's frames are restored, at the original host-call instruction. Admission accounts this storage;
factory allocation and unwinding remain sliceable and do not retire the published call a second time.

## Runtime ABI 1.10

Runtime ABI 1.10 adds reference-default form 7 of `string_value_of`. It accepts nullable references and returns
non-null String: `null` for absence, otherwise the qualified runtime type name followed by `@` and lowercase
hexadecimal VM Ref32 identity. Identity is stable for a live object, never a host pointer. Admission deduplicates
verified type names into shared UTF-16 storage bounded by source metadata bytes and type records; construction uses
existing charged, sliceable scan/allocation/copy and GC machinery without publishing partial strings.
The compiler-owned `kotlin` library supplies concrete virtual `Any.toString` and overrides for String, typed scalar
boxes and the Unit singleton. Ordinary CallVirtual selects user/library overrides; super calls remain direct.
Arrays inherit the root dispatch slot. ABI 1.10 allows methods on the stateless root parent of arrays and Throwable;
fields, interfaces, superclass and initializer restrictions remain. Form 7 and this root-method allowance require
1.10 in both validators; the linker infers that minimum from retained instructions. The container format and
C ABI 18 were unchanged at introduction. Newly built platform libraries and programs use the updated canonical runtime module.

## Runtime ABI 1.11

Runtime ABI 1.11 adds `value_hash` (opcode `0x69`), with forms 1/2/3/5/6/7 for Int/Long/Float/Boolean/Char/reference.
Both validators require the matching initialized source type and an Int destination. Each hash costs one fixed
instruction without allocation: Int and Char use their bits, Long folds its halves, Boolean uses 1231/1237, Float
canonicalizes NaN while preserving signed zero, and references use live VM identity with null zero. Identity remains
stable in the current nonmoving managed heap; a future moving collector must preserve the observable hash.
String hashing reuses the charged, sliceable UTF-16 `string_hash` instruction, retaining its source as a GC root.
The canonical library adds virtual `Any.hashCode` and overrides for String, typed boxes and Unit. Ordinary and library
overrides use virtual dispatch; superclass calls remain direct. The stdlib `Any?.hashCode` extension returns zero for
absence and dispatches present values. Generated data-class hashes combine primary-constructor property hashes with
wrapping Int multiplication by 31; body properties are excluded. Supported array properties use a charged Guest
loop over element hashes with initial value one and null zero, while ordinary arrays keep identity hashing.
The linker infers ABI 1.11 from retained `value_hash` instructions. Container format and C ABI 18 were unchanged at introduction;
`stdlib:core` 1.8.0 adds strict `Iterable.first` selection and the ordinary
managed `NoSuchElementException` class; these use existing instructions and exception representation.

## Runtime ABI 1.12

Runtime ABI 1.12 extends `string_value_of` (`0x68`) and `value_hash` (`0x69`) with
form 4 for F64. Both the writer and Rust admission reject these forms below ABI 1.12;
linking infers the requirement from retained instructions. Double hashing canonicalizes
NaN to `0x7ff8000000000000`, then folds high and low words with xor. Equality compares
values in addition to hashes, so hash collisions never imply Double equality. Generated
data-class comparisons remain unboxed and use Kotlin floating total order. Scalar text
formatting uses a fixed 24-unit stack buffer and existing sliced managed-string publication;
F64 retains shortest-round-trip decimal precision and three-digit exponents. Numeric F64
operations already belong to ABI 1.0. Artifact format and C ABI 18 remain unchanged.

## Runtime ABI 1.13

Runtime ABI 1.13 adds structured asynchronous host results through the existing C ABI 19 resume-value boundary.
Capability schema wire version 2 carries an ordered nominal record tree; scalar-only schemas remain version 1.
Response tag 8 carries qualified ASCII type names, ordered named fields and exact scalar or nested-record values.
Limits are 8 nesting levels, 32 record/String nodes, 64 expanded fields, 4096 aggregate UTF-16 code units and 64 KiB.
The SDK accepts public immutable acyclic data records with scalar or nested-record fields; nullable fields, arrays,
record arguments and custom construction logic are unsupported. Bundle format CAGB 3 and addon ABI lock format 2
retain record shape; scalar-only bundles and locks keep their older encoding.

Rust admission checks the asynchronous destination's nominal name, instance layout and field storage against the
host schema. Resume validates and copies the response, without executing Guest code or allocating Guest objects.
Advance materializes the tree under existing budgets, keeps partial nodes rooted through GC and publishes the root
only when complete. Other tasks' replies queue without replacing unfinished reference materialization. Owned reply
buffers and pending plans contribute to execution-resident accounting. Structured programs require Runtime ABI 1.13;
rebuild addon bundles and programs. Artifact encoding and the native export inventory are unchanged.

## Runtime ABI 1.14

Runtime ABI 1.14 adds explicit primitive-array payload storage to array type flags. Bits 1..3 select
natural (0), signed 8/16-bit (1/2), unsigned 8/16/32/64-bit (3/4/5/6) storage; bit 0 keeps the optional
array superclass. Packed values load with sign/zero extension into I32 registers; ULong storage uses I64.
Both writers and Rust admission require the matching scalar element kind and ABI 1.14. Compatibility
checks include storage so a packed array cannot be used as a natural-width array. Heap accounting and
sliced copying use payload widths. Container format and exported native C ABI remain unchanged.
Unsigned numeric forms 8/9 interpret I32/I64 bits for arithmetic, equality/order and decimal text.
`convert` carries source/destination unsigned bits while form 0 preserves signed conversion.
Unsigned widening zero-extends; floating conversions truncate/saturate with NaN or negative input
to zero. Unsigned integer-to-F64 rounds to nearest with ties to even; F32 follows the Kotlin
standard library conversion through F64. Verifiers reject
wrong operand kinds, unknown masks and legacy ABI claims. Zero divisors use the existing managed
arithmetic exception.

## Runtime ABI 1.15

Runtime ABI 1.15 introduces nominal inline value layouts. Type record tag 4 has zero flags/arity,
a name u32, component count u16, reserved zero u16 and flattened primitive/reference value-type records.
Semantic value kind 8 carries a nominal type reference and zero flags. Inline layouts are nonempty,
cannot contain Unit or another inline layout, and are distinct from managed reference types. Function
physical shapes must exactly match their nominal leaves. Heap fields and array elements use managed
boxes rather than inline layouts.

`inline_construct` (0x05, form 0) encodes destination u16, component count ULEB and source u16 registers;
`inline_component` (0x06, form 0) encodes destination/source u16 and component index ULEB (at most u16).
Costs are 2 + component count and 2 respectively. Move, call arguments, task-spawn arguments and returns
charge one extra unit per additional inline component copied. Copies stay in compact frame storage;
REF32 leaves participate in exact component safepoint maps. Nominal identity, initialization, leaf
types, physical shapes, ABI gates and declared costs are checked by both Kotlin and Rust. Artifact
container format 3 and exported C ABI 20 are unchanged; host entry/results retain their scalar contract.

## Runtime ABI 1.17

Runtime ABI 1.17 adds `matrix_math` (`0x1e`, form 0). The framed operands are a closed ULEB selector,
destination u16, left u16 and, for binary selectors, right u16. All values are nominal inline layouts
of homogeneous F32 components; the left matrix always has sixteen row-major components. Verification
checks exact arity, initialized inputs, component counts/types, physical shapes, ABI and declared costs.
Nominal identities may differ when layouts match. Kotlin writing/linking infers the minimum ABI from
retained instructions; older ordinary Float artifacts remain usable. Container 3 and native C ABI 23 are unchanged.

| Selector | Operation | Right components | Result components | Fixed cost |
| --- | --- | --- | --- | --- |
| 1 | Transpose | absent | 16 | 18 |
| 2 | Matrix multiplication | 16 | 16 | 128 |
| 3 | Vector multiplication | 4 | 4 | 32 |
| 4 | Point transform (`w=1`) | 3 | 4 | 32 |
| 5 | Direction transform (`w=0`) | 3 | 4 | 32 |

Costs are bounded budget units, not timing guarantees. Kernels snapshot both inputs on the host stack
before writing, allowing destination/input aliases without managed allocations or suspension. Dot products
multiply each F32 pair and add strictly left to right, rounding and canonicalizing NaNs at each scalar
operation. No FMA, reassociation or omitted zero terms: infinite translation times `w=0` produces NaN.
Transpose copies original bits, including signed zeros and NaN payloads. Factories continue to use stdlib
code and existing trigonometric intrinsics. Checkpoints retain logical inline values with existing framing.

## Runtime ABI 1.16

Runtime ABI 1.16 adds `math_unary` (`0x1c`) and `math_binary` (`0x1d`), with form 3 for F32
and form 4 for F64. Operands are a closed ULEB operation selector, destination u16, then one
source u16 or left/right u16. Both verifiers reject other forms, unknown selectors, uninitialized
sources, mismatched floating widths and ABI claims below 1.16. Linking infers the minimum from
retained math instructions. Container format 3 and checkpoint framing remain unchanged; native C ABI 22 includes byte-array host values.

Operations use pinned software `libm` 0.2.16, without system math calls, allocation or suspension.
NaNs follow the VM's canonical representation; IEEE signed zero is retained. `round` uses ties to
even; `min`/`max` propagate NaN and select negative/positive zero respectively. `pow` follows Kotlin's
special cases, including NaN for a base of magnitude one with an infinite exponent. Integer and
library-derived operations use ordinary Guest code. Fixed costs below apply to both widths and are
included in block admission and execution budgets; they are budget units rather than timing guarantees.

| Unary selector | Operation | Fixed cost |
| --- | --- | --- |
| 1 | `abs` | 2 |
| 2 | `sign` | 2 |
| 3 | `ceil` | 4 |
| 4 | `floor` | 4 |
| 5 | `truncate` | 4 |
| 6 | `round` | 4 |
| 7 | `sin` | 64 |
| 8 | `cos` | 64 |
| 9 | `tan` | 64 |
| 10 | `asin` | 64 |
| 11 | `acos` | 64 |
| 12 | `atan` | 64 |
| 13 | `sinh` | 64 |
| 14 | `cosh` | 64 |
| 15 | `tanh` | 64 |
| 16 | `asinh` | 64 |
| 17 | `acosh` | 64 |
| 18 | `atanh` | 64 |
| 19 | `sqrt` | 16 |
| 20 | `cbrt` | 64 |
| 21 | `exp` | 64 |
| 22 | `expm1` | 64 |
| 23 | `ln` | 64 |
| 24 | `ln1p` | 64 |
| 25 | `log10` | 64 |
| 26 | `log2` | 64 |
| 27 | `ulp` | 4 |
| 28 | `next_up` | 4 |
| 29 | `next_down` | 4 |

| Binary selector | Operation | Fixed cost |
| --- | --- | --- |
| 1 | `min` | 2 |
| 2 | `max` | 2 |
| 3 | `atan2` | 64 |
| 4 | `hypot` | 32 |
| 5 | `pow` | 64 |
| 6 | `ieee_rem` | 64 |
| 7 | `copy_sign` | 2 |
| 8 | `next_towards` | 4 |

## Release composition inventory

Release transfer tooling emits `release.json` schema 2 after the existing release gates. It records the tagged
parent and VM revisions, target artifact digests, explicit bundled distribution mode, and the shared tooling
bundle/manifest/carrier identities. Schema 2 adds a required ordered `addons` inventory for Create, Sable and
Propulsion, with their independent API versions, target Compukters line, loader, Minecraft version, lengths and
digests. GitHub receives all five archives; Modrinth consumes only the two base-mod `artifacts`.
Consumers retain schema 1 support for retries of previously staged two-JAR releases.
The transfer consumer admits only the autonomous composition in both schemas;
an external component mode requires an explicit contract extension. This inventory does not change Runtime ABI,
worker protocols or the packaged tooling manifest. See [release publication](../releases.md) and the
[transfer contract](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/tools/release/README.md).

### Interactive display input

Display capability 1.2 preserves operations 0–16 and appends operation 17, `pollClick(handle: I32): BYTE_ARRAY`.
Minor 0/1 requests cannot invoke operation 17. An empty array means no event; otherwise exactly eight bytes
contain unsigned little-endian x and y in published canvas pixels. `compukter:core` 7.3.0 wraps this private
encoding as `GraphicalDisplay.pollClick(): DisplayClick?`, with a `Position2i` position.

Empty-hand front-face right-clicks use renderer-aligned coordinates for all four orientations and add panel
offsets in a composite canvas. Shift-use passes through. Server-thread input is a FIFO limited to 64 entries;
overflow drops new arrivals. Input belongs to the program's exclusive writer lease. Polling claims that lease.
Lease release/expiry, world restore, layout retirement and committed density changes clear input. Aborted
frame density changes preserve it. Pending queue entries are never included in canvas or program resource checkpoints. Values already returned
to Guest code are ordinary program data and follow its normal execution/checkpoint semantics.


## Display capability 1.3

`compukters:display` 1.3 preserves operations 0–17 and appends three host operations; schema wire encoding,
Runtime ABI 1.20, C ABI 23 and CPKT 3 remain unchanged. Guest module `compukter:core` is 7.4.0.

| Operation | Arguments | Result |
| --- | --- | --- |
| 18 `pollPointer` | handle: I32 | BYTE_ARRAY |
| 19 `lineClipped` | handle, x0, y0, x1, y1, color, clipX, clipY, clipWidth, clipHeight: I32 | UNIT |
| 20 `textClipped` | handle, x, y: I32; text: STRING; color, scale, clipX, clipY, clipWidth, clipHeight: I32 | UNIT |

Polling returns an empty array or exactly 16 bytes: four signed little-endian I32 fields kind, gesture,
x and y. Kinds are Down=0, Drag=1, Up=2, Cancel=3. Positive IDs identify independent transient gestures;
Cancel with ID 0 cancels every capture. Coordinates use published whole-screen pixels. Input shares the
program's exclusive display lease, is bounded to 64 queued events and eight active gestures, coalesces
adjacent moves and cancels all gestures on overflow. Release/reset never replay events; a published density
change emits cancellation while an aborted density change preserves the stream.

Clipping rectangles have nonnegative sizes and intersect the active canvas using Long endpoint arithmetic.
Line clipping occurs before bounded rasterization; text masks charge and touch only the intersected region.
Operations 18–20 reject capability minors below 3. Existing operations remain available at their previous
minimum minors. Kotlin source declarations and compiled export linking include mutable property accessors.
Callback source syntax is normalized to canonical function types during linking; source metadata preserves
extension-function receivers. Callback interfaces share specialization identity across library boundaries.

## Display capability 1.4

`compukters:display` 1.4 preserves operations 0–20 and appends asynchronous operation 21
`rasterBatch(handle: I32, bytes: BYTE_ARRAY): UNIT`. Minors below 4 reject operation 21.
Guest module `compukter:core` 8.0.0 changes the `GraphicalDisplay.frame` receiver to `DisplayFrame`;
canvas/widget/UI drawing accepts this scoped recorder. Immediate scalar drawing and device input remain on
`GraphicalDisplay`. CPKT, Runtime ABI, native transports and checkpoint encoding are unchanged.

Packets contain at most 4096 bytes: little-endian I32 version (1), I32 command count (1–128), then commands.
Each command starts with a U8 tag matching its existing scalar operation. All following scalar fields are
signed little-endian I32; strings are an I32 UTF-16 code-unit count followed by little-endian U16 units.

| Tag | Fields after tag |
| --- | --- |
| 8 | color |
| 9 | x, y, color |
| 10 | x0, y0, x1, y1, color |
| 11 | x, y, width, height, color |
| 12 | x, y, string, color, scale |
| 13 | x, y, pixel count (1–256), that many 24-bit RGB colors in I32 fields |
| 19 | x0, y0, x1, y1, color, clipX, clipY, clipWidth, clipHeight |
| 20 | x, y, string, color, scale, clipX, clipY, clipWidth, clipHeight |

The host decodes and validates the whole packet before drawing. Unknown tags/versions, invalid counts,
truncation, trailing bytes, invalid colors, negative sizes and malformed UTF-16 reject. Existing text limits
(256 code points, 1024 UTF-8 bytes, scales 1–8), clipping and deterministic pixel-work limits remain in force.
Batching requires a live endpoint, an exclusive program lease and an open frame owned by the requesting task.
Any decode or application failure discards that frame; commit then fails. Abort is idempotent when no frame
remains, allowing Guest `finally` cleanup without masking the original error. A wrong-task request cannot
abort the owner's frame. Multiple packets retain drawing order, and only commit publishes pixels.

The Guest recorder is mutable and task-confined; do not share it between cooperative tasks. A failed submission
poisons it and successful/failed block exit closes it, so an escaped recorder cannot submit another frame's
commands. Packet buffers are ordinary managed Guest memory and follow VM hibernation with the host's existing
private framebuffer checkpoint. No global handle-to-buffer registry exists.

### Classic Kotlin contracts

`kotlin:builtins` 1.13.0 supplies the structural `kotlin.contracts` DSL, opt-in annotations and
annotation class tokens. K2's existing native metadata serialization preserves classic effects;
these compiler declarations have no library runtime exports. Annotation defaults are serialized
by FIR and are excluded from the separate executable-call default model. The platform bundle
encoding, worker protocol, CPKT instructions and Runtime ABI do not change. Definite assignment
uses ordinary registers and capture cells; reference cells awaiting assignment have nullable
storage and typed reads.

`stdlib:core` 1.20.0 publishes scope/assertion/buildString effects, and `compukter:core` 7.5.0
publishes synchronous display frame and nested UI construction effects. Existing native K2 metadata
carries these effects for compiler and IDE consumers, including when SDK sources are detached.

## IDE device management transport

IDE target payload registration version 2 extends bounded named-peripheral entries with optional exact instance/kind
references and up to eight validated fully qualified Guest lookup types. A lease-authorized rename request carries
that reference, an expected previous name and a bounded requested name; success returns refreshed discovery.
Version 1 peers cannot decode the extended entry layout and are rejected by target payload registration.
The optional lookup type constructors in ComputerPeripheralContract and CompuktersPeripheralContract retain all
existing JVM constructor signatures. Missing metadata disables typed clipboard lookup generation only.
Guest capabilities, platform bundles, saved network records and native Runtime ABIs are unchanged.

### Programmable Create actuators

Create addon API 2.1 preserves capability `create:create` major 1 operation IDs 0–29 and appends:

| ID | Binding | Arguments | Result |
| --- | --- | --- | --- |
| 30 | `create.actuators.ActuatorBindings.pumpReversed` | `Int` handle | `Boolean` |
| 31 | `create.actuators.ActuatorBindings.setPumpReversed` | `Int` handle, `Boolean` direction | `Boolean` |
| 32 | `create.actuators.ActuatorBindings.clutchConnected` | `Int` handle | `Boolean` |
| 33 | `create.actuators.ActuatorBindings.setClutchConnected` | `Int` handle, `Boolean` connection | `Boolean` |

The typed contracts are `create:pump` and `create:clutch`. Guest wrappers are `create.actuators.Pump` and
`create.actuators.Clutch`; common peripheral discovery owns acquisition and exclusivity. Pump reversal persists in
block state. Clutch command ownership is a transient SDK lease and never survives a world reload or peripheral
checkpoint. These endpoints provide no persistent-instance identity; old handles restore as stale. This extends
only the locked addon schema; native ABI, artifact encoding and capability wire format remain unchanged.
