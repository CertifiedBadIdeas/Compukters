---
layout: default
title: Compiler, platform and library linking
section: contributors
permalink: /ARCHITECTURE/compiler/
---

# Compiler, platform and library linking

[← Architecture](../architecture.md)

* On this page
{:toc}

## Platform declarations and value representations

Built-in Guest Kotlin declarations are authored in `guest-platform` as separately versioned modules. Its build
produces only the canonical base platform bundle consumed by both compilation and IDE analysis. Optional integrations
own and build their Guest declarations as addon bundles against that base; `addons/create`, `addons/sable` and `addons/propulsion` own independent integration bundles.
The public `ru.lazyhat.compukters.addon` Gradle plugin resolves an isolated builder, base bundle, Minecraft-independent
host API, and thin target adapter from one independently versioned SDK release by Maven coordinate. The SDK version
changes with its public compatibility boundary rather than every Compukters release. The plugin derives the wire
schema and bindings from Guest Kotlin declarations, validates a checked-in selector lock, generates the typed JVM host
contract, and packages the resulting `.cagb` in the independent addon JAR.
`platform-bundle` owns the base bundle model, codec, module graph, and default imports; `platform-k2` exposes that
metadata to K2 without making the K2 implementation part of the platform format.

## Default arguments and loops

Constant `Int` and qualified enum-entry defaults cross this bundle explicitly; compiler lowering materializes an
omitted platform argument without a JVM-style mask dispatcher. The bundle also represents explicit null defaults and array-receiver-size defaults. Arbitrary default expressions remain limited to the documented Guest subset.
Canonical `IntRange` and `IntProgression` declarations let K2 resolve ascending, descending, and stepped `Int` loops.
The compiler recognizes only their canonical `for` shape and evaluates start, end, and step once into scalar registers;
no range or iterator object enters the VM. It validates a positive step before iteration, calculates the next index in
`Long` to avoid `Int` overflow, and retains a loop-header quota safepoint. Stored progressions and non-Int range loops use ordinary Guest library classes and iterators; the allocation-free optimization is specific to the recognized direct `Int` shape.
Direct primitive-array `for` loops follow the same canonical-shape rule: the compiler snapshots the array reference, loads
its length, and reads each element with existing array instructions at the current index. The loop header retains its
quota safepoint; no iterator object is created. A source variable may be reassigned without changing the traversed
array, while writes to that array's elements remain visible to later iterations.

## Value representations

Scalar value classes retain their scalar representation for direct typed calls. Nullable/Any/interface boundaries and reference
collection storage use ordinary nominal managed wrapper classes with payload fields matching the value layout. Canonical platform and addon
libraries export the wrapper type and payload field through existing type/field links; consumers import these instead
of introducing a second nominal identity. Source value classes own their application-local wrappers. Equality checks
nominal type and payload, hashing uses the payload, and virtual toString dispatch preserves default or custom rendering.
Wrappers carry the value class's interface parents. The established direct scalar fast path applies to single `Int`/`Boolean`/`Char` layouts; other payload layouts use nominal inline values. Concrete interface implementations get virtual bridges that
read the scalar receiver and call its static implementation; an underlying-property getter returns that payload
directly. Inherited interface defaults use existing interface dispatch, including concrete generic specializations.
Scalar boxing uses ordinary managed classes and the established ABI. Multi-field and other non-scalar
value layouts use ABI 1.15's nominal inline types and compact frame components for direct calls and locals.
Reference contexts box flattened payloads through ordinary managed fields; nested source properties retain
source grouping for hashing/text. Libraries export the direct inline type separately from its managed class
and export every payload field. Consumers import both identities, including concrete generic layouts; linker
signature matching resolves nominal inline identities across modules. Bound method references store the
boxed receiver in their closure environment and reconstruct its direct layout before static invocation.
Constructor references reuse constructor lowering, including argument layout and initializer execution.
Compiler and IDE sessions share `CompuktersLanguageVersionSettings` so native multi-field declarations
receive consistent frontend analysis.

Nullable `String` and supported Guest class source references use the artifact's existing nullable reference types and
`Null` instruction. K2's safe-call and Elvis branches lower through ordinary verified control flow, with reference
casts when a non-null branch joins a nullable result. All twelve nullable primitives use their distinct nominal managed boxes or null. Safe casts and non-null assertions on value classes preserve their direct layout; see the [language contract](../../developers/kotlin/nullability.md) for the tested boundary.

## Compilation, specialization and linking

The trusted server JVM owns compiler scheduling, diagnostics, artifact production, and the server-global
content-addressed cache. The Rust machine validates guest paths, captures an immutable source snapshot plus filesystem
preconditions, and suspends only the requesting foreground process. The server tick submits and polls bounded compiler
work without waiting on the worker or cache. On completion Rust re-verifies the complete artifact and atomically
installs it only if the source and output preconditions still match. Native buffers remain caller-owned and no Rust
pointer enters Kotlin.

## Compiler preparation and caching

Compiler service initialization runs in `ServerStartingEvent`, after the world filesystem store is opened.
Ordinary computer creation and target-profile requests require an already prepared service and cannot lazily unpack
worker resources on the server tick. Worker compilation remains asynchronous; this changes the preparation phase,
not the compilation scheduler or wire formats.

The packaged tooling payload is validated and published beneath `<world>/compukters/compiler-worker`; temporary
worker state is kept separately beneath `<world>/compukters/compiler-temp`. Successful server artifacts are stored
beneath `<world>/compukters/compiler-cache/v1`, shared by every computer and dimension in that server world, and reused
after restart. Cache keys cover the ordered source snapshot, compiler and payload identity, target, selected trusted
platform modules, and compilation limits. Cache publication and cache hits both pass the stateless Rust artifact
verifier over FFM; runtime admission quotas are deliberately not part of cache validity.

## Specialization and library ownership

Guest generic functions and supported generic classes are specialized before artifact writing. Final classes,
concrete value classes and the admitted abstract/open generic provider hierarchies share the bounded specialization model. Function and class
specializations are discovered together until no further dependencies appear, including helpers called from generic
members whose bodies introduce other generic classes. The VM receives ordinary
concrete function and class records, with typed scalar or reference fields and calls. Platform ABI 3 records the
symbol and signature of each declaration requiring source compilation: generic implementations, classes with generic
supertypes, members of those classes, and inline bodies. One module can retain an ordinary precompiled library fragment
alongside those source bodies. The compiler resolves each source library as a separate Kotlin module over one shared
metadata session, preserving `internal` and `private` boundaries. It includes canonical sources for resolution and
specialization but binds ordinary implementations, including private helpers, to their compiled fragment instead of
emitting them again in the consumer. Kotlin source visibility still governs access to those helpers. Ordinary function
exports use the qualified declaration symbol and canonical signature, so same-name functions in different packages or
owners remain distinct even when their lowered signatures coincide. Fragment assembly retains exported
implementations and their dependencies; final application linking removes unreachable records. Tooling normalizes
dependency module indexes into symbolic identities before linking so a shared library retains one owner across
fragment containers. The final executable restores concrete indexes. Generic binary templates are not part of the
platform bundle or VM artifact contract. Bundle format 9 and standalone module format 5 reject older representations.
Selected addon modules can provide source templates through the same verified source-library path. Their metadata
carrier retains template declaration identities while the separate source carrier supplies the matching files;
sources are reattached before template validation. Standalone module decoding still rejects detached templates.
Managed object/companion instances use existing class initializers and static fields, including exported singleton
fields for independent libraries. Typed callback interfaces use names derived from their complete signatures and
participate in trusted specialization ownership, so producer/consumer discovery order cannot change their identity.
These changes reuse the existing bundle and artifact representations and do not change the native ABI.
Default-argument metadata includes an explicit array-receiver size expression, evaluated from the already computed
receiver rather than re-evaluating its source expression, and an explicit null value distinct from an absent default.
Private Kotlin metadata format 6 carries the same defaults, including primary-constructor defaults without a receiver slot.
Ordinary public library constructors are linked as static functions receiving the already allocated object;
direct construction and superclass calls share argument/default evaluation. Their Kotlin bodies are compiled in
the owning library, not regenerated or bypassed by the consumer.
The standard library has one owner, `stdlib:core`: core helpers, inline scope functions, `repeat`, ranges and collections.
Its generic and inline source bodies coexist with ordinary precompiled implementations.

Ordinary library implementations may materialize concrete generic classes and structural collection interfaces,
including `ArrayList<String>` and `List<String>`. These variants have canonical internal type, constructor, method
and field exports in the owning library artifact. Linking redirects matching trusted template materializations to
that owner and removes the superseded local records; variants absent from dependencies continue to specialize from
source. Source provenance, rather than a user class's spelling, controls eligibility. Ambiguous owners, incompatible
layouts and missing members are rejected. Internal linking exports do not change Kotlin source visibility. The
representation uses the existing artifact import/export format, without new VM instructions or binary generic
templates. Only concrete variants are shared; inline and generic top-level source bodies retain their normal
consumer specialization behavior. K2 still materializes a consumer variant transiently for type and layout checking;
this linking contract does not yet skip that lowering work. Trusted collection variants include their supported
read-view interfaces regardless of consumer usage, so covariance cannot change a specialization's nominal layout.
User-defined concrete type arguments retain their qualified identity, with a separate root-package namespace, rather than
Kotlin metadata's short display names. User classes named `String` therefore cannot reuse a built-in String variant.

## Built-in module graph

The complete built-in graph is `kotlin:builtins` → `stdlib:core` → `compukter:core`. Compukter core owns environment
and VM-runtime APIs: compiler, child processes, redstone, sound, text displays, terminal, filesystem, and cooperative
tasks. This includes the environment-dependent `kotlin.io` facade without changing its package or default
imports. Module ownership changes the canonical platform content identity, not capability operation schemas or the
VM executable ABI. Tooling and addons using removed split-module IDs must rebuild against the consolidated owners.

## Collection storage and universal views

The standard library publishes read-only lists and public `ArrayList<T>` with source bodies for specialization.
`listOf` and `emptyList` create fresh `ArrayList<T>` instances exposed as `List<T>`, with capacity equal to the
number of elements. Factories fill them through the specialized `add` method in source order. A read-only view can be
cast to `MutableList<T>` or `ArrayList<T>` and changed; it is not an immutable object. All twelve non-null primitive families use their corresponding primitive arrays, including compact Byte/Short and unsigned storage; reference and nullable storage uses typed reference arrays. Collection interfaces
and iterator methods are specialized in the consuming artifact. Covariant widening to `List<Any>` retains the same
list and backing array. The compiler publishes `get`, `iterator`, and `next` bridges with reference result signatures
alongside the typed methods. An `Int` read through `Any` allocates an ordinary managed `kotlin.Int` box; a reference
read preserves the element reference. The box's runtime type and i32 payload support checked `is Int` and `as Int`,
while typed paths remain unboxed. Direct `List<Any>` factories use a local nominal `Array<Any>` whose reference slots
hold boxed `Int` and supported objects; each value is converted once before storage. Direct source `Array<Any>`
factories and indexed writes use the same boxing boundary; array reads return the stored reference. `Collection<T>`
owns `size`, `isEmpty`, and `contains`; specialized lists publish an `Any` argument bridge for covariant `contains`
calls. `Iterable<T>` search extensions `contains`, `indexOf`, and `lastIndexOf` scan with the iterator, while the
`List<T>` index methods use direct indexed access and publish `Any` and `Any?` argument bridges for covariant calls. The
`Iterable<T>` predicate extensions `any`, `all`, and `none` specialize both the element and function value signature
before lowering calls to the predicate.
`MutableList<T>` is invariant. `ArrayList<T>` uses a typed storage interface, with the compiler selecting the matching primitive or
reference storage at its trusted factory call. Growth, shifting, searches, slot clearing, and mutable iteration are
ordinary Guest bodies; all allocations use the VM's existing metered array instructions. Read-only aliases share
the same list. Iterator bridges expose both read-only and mutable result signatures, and a modification counter
rejects stale iterator `next`/`remove` calls after structural mutations.
`ArrayList` is the sole library list implementation. Its indexed-search read-view helpers live alongside it in
`ArrayList.kt` and operate over `List<T>` to preserve specialization for typed, nullable and universal reads;
read-only and mutable views share the same iterator implementation with mutation checks and removal state. The compiler
uses one trusted collection read-view registry for bridge method names and additional interface types.
Library lists also publish nullable element read bridges for read-only views such as `List<Int?>` over `List<Int>`.
These views share storage: scalar reads allocate boxes, and reference reads retain identity. `filterNotNull` narrows
elements into a new typed list, unboxing nullable Int values and retaining non-null references. Generic smart-cast
arguments use checked reference conversions against their instantiated non-null parameter types.
Generic call arguments are converted against the specialized parameter types, so searching a widened `List<Any>`
boxes an `Int` argument before comparison. Universal equality over references uses the canonical virtual
`Any.equals(Any?)` method. Nullable `==` evaluates both operands once, compares null left receivers by identity,
and dispatches non-null left receivers. A literal-null comparison remains a reference test; explicit `.equals(null)`
still invokes the receiver. Root Any and arrays use identity, String compares UTF-16 content, and typed boxes compare
values with canonical NaN and distinct signed zero for Float. Primitive Float `==` retains IEEE semantics.
Generated data-class equals is an ordinary class method: it checks identity/type and compares primary-constructor
properties through the same scalar/reference contract, including nullable and object fields. Array properties compare
identity, while their generated hash uses content. Body properties do not participate. Custom/inherited methods and
compiled libraries share dispatch; there is no compiler-side enumeration of class layouts at each equality call.
Each nullable primitive uses a nullable reference to its nominal managed box. Conversion to the direct scalar checks the box and reads its
payload; nullable and Any boundaries preserve existing box references. Virtual hashCode and reference-default string
conversion are described in the [ABI reference](abi.md#runtime-abi-111) (Runtime ABI 1.11 and 1.10). This equality implementation reuses ABI 1.11
instructions without adding an opcode, C ABI change or persisted-state format. Compiled libraries must rebuild
against the updated canonical runtime module.

Nullable reference arrays have distinct nominal types with nullable element descriptors. `Array<Int?>` and
`List<Int?>` store the same managed Int boxes or null, while `List<Int>` retains scalar storage. Nullable and non-null
lists expose `Any?` bridges for reads, iteration, and searches.

## Reference arrays and register allocation

Concrete `Array<GuestClass>` uses become distinct local nominal array types whose elements are exact Guest class
references. Existing array instructions allocate, load, and store them; the verifier checks element types and GC
follows live references in their slots. Execution tracing consults each frame's safepoint map before resolving a
reference register, so a dead register left after GC cannot invalidate a running program.

After reachability pruning and relocation, the linker reuses temporary registers whose lifetimes do not overlap.
Allocation uses the same control-flow analysis as GC roots, including loop back edges, exceptional edges and resume
blocks. Only identical semantic types and physical shapes share a register; parameters, caught-exception destinations
and operands in unreachable code retain dedicated slots. Inputs and destinations remain distinct through an instruction,
including allocation and suspended calls. Instruction order, debug boundaries and metered costs are unchanged. Root maps,
linked module hashes and frame storage requirements are rebuilt after allocation. The artifact encoding and runtime
layout/verifier contract are unchanged; already compiled artifacts retain their original frame storage.

`ServerCompilerService` performs bounded asynchronous preparation, persistent-cache lookup, and single-flight
deduplication by compilation identity. Minecraft-facing code submits requests and drains completions; worker and cache
I/O run outside the server tick thread.
