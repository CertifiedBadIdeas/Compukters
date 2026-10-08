---
layout: default
title: ABI and representation reference
section: contributors
permalink: /ARCHITECTURE/abi/
---

# ABI and representation reference

[← Architecture](../architecture.md)

This reference groups versioned representation boundaries by the version that introduced them. Those versions are minimum feature requirements, not a claim that older Runtime distributions implement today’s platform. The source owners below define the active contract. Before changing a wire format, inspect each producer, consumer and verifier. Runtime release pins in `build-scripts/src/main/kotlin/RuntimeBundleSupport.kt` describe published bundles separately from the local VM checkout.

## Boundary identities

| Boundary | Current representation | Owner |
| --- | --- | --- |
| Executable container | Format 3; floating math requires Runtime ABI 1.16 | `modules/common/compiler-artifact` and `host/compukter-vm/src/artifact/format.rs` |
| Native session transport | C ABI 21, checked by both FFM and JNI | `host/compukter-vm/ffi/src/lib.rs` and `modules/common/native-runtime` |
| Base platform | Bundle format 9, standalone module format 5, platform ABI 3 | `PlatformBundleCodec` in `modules/common/platform-bundle` |
| Kotlin metadata carrier | Private format 6 | `platform-k2` |
| IDE analysis | Protocol 15 | `ide-analysis-client` and `ide-analysis-k2` |

## Guest hash collection module identities

Guest hash collections introduce public Set/Map contracts in `kotlin:builtins` 1.9.0 and ordinary source
implementations, Pair and populated factories in `stdlib:core` 1.11.0. Their module content identities change; bundle format 9, module
format 5, platform ABI 3 and Runtime ABI 1.15 remain unchanged by hash collections. The current native C ABI is 21 for checkpoint transport. Programs and dependent
platform/addon inputs must resolve the matching module identities. Hashing, generic equality, managed
objects and specialization reuse existing instructions and representation rules.
## Guest math module identity

`stdlib:core` 1.12.0 adds the portable `kotlin.math` surface. Floating primitives require Runtime ABI 1.16;
integer and derived wrappers use ordinary Guest code. Canonical source getters retain extension-receiver
identity, including the distinct Int and Long `sign` getters with the same Int result. Platform bundle 9,
standalone module 5 and private metadata carrier 6 remain unchanged; rebuild dependent module inputs.

## Execution checkpoint development

The VM has a logical computer checkpoint envelope (format 2), owned by
`host/compukter-vm/src/checkpoint/envelope.rs`. Its version, native runtime/schema identity, computer ID,
filesystem generation, payload lengths and SHA-256 cover the execution and host descriptor bytes together.
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
rebinds an old handle to a replacement. Display identities live in block-entity persistence; display resource
format 2 restores owned rows and writer leases only after peripheral admission and without overwriting
another active writer.

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

Compact debug paths retain container format 3.0 and semantic Runtime ABI 1.15; the current native C ABI is 21 for checkpoint transport. New readers accept
legacy artifacts; readers without DEBUG_PATHS support reject its critical section. This is a reader capability
requirement independent of semantic ABI. Published Runtime 0.20.0 bundles predate this support: compact output
requires a Runtime rebuilt from the updated source, and a future published bundle/pin update before packaging
with released natives. This source change alone does not establish production-bundle or release compatibility.

Native C ABI 17 appends a length-prefixed, bounded UTF-8 trace to terminal outcome tags 2 (OOM), 5 (Guest trap), and
6 (VM fault), after their existing scalar payload. Empty text means unavailable diagnostic text. FFM and JNI validate
ABI 21 before decoding; both retain typed failures and carry the trace through the runtime host. Other wire tags and
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
Runtime ABI 1.15 remain unchanged by root ranges; the current native C ABI is 21 for checkpoint transport. Readers predating the marker reject it as unknown critical.
Updated source-built natives are required; published Runtime 0.20.0 bundles and their pins do not contain this capability.


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

## Runtime ABI 1.16

Runtime ABI 1.16 adds `math_unary` (`0x1c`) and `math_binary` (`0x1d`), with form 3 for F32
and form 4 for F64. Operands are a closed ULEB operation selector, destination u16, then one
source u16 or left/right u16. Both verifiers reject other forms, unknown selectors, uninitialized
sources, mismatched floating widths and ABI claims below 1.16. Linking infers the minimum from
retained math instructions. Container format 3, native C ABI 21 and checkpoint framing remain unchanged.

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
