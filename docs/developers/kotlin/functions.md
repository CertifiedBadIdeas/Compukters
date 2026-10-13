---
layout: default
title: Functions, generics and callbacks
section: developers
permalink: /KOTLIN-SUPPORT/functions/
---

# Functions, generics and callbacks

[← Guest Kotlin support]({{ '/KOTLIN-SUPPORT/' | relative_url }})

* On this page
{:toc}

This page describes the current checkout, including unreleased work. [Status and evidence policy]({{ '/KOTLIN-SUPPORT/#status-and-evidence' | relative_url }}) applies to every entry.

## Top-level and member calls

**Status:** Partial.

Direct top-level calls, immutable property getters, and supported member operations lower by exact symbol.
Only admitted declarations and signatures are executable. Supported virtual/interface dispatch follows the [object-model contract](objects.md).

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `multi-file terminal program lowers through trusted symbols` and `same-named guest function remains an
ordinary project call`, plus `same-named guest calls preserve their resolved targets for vm conformance`
paired with `testKotlinNamedCallsVmConformance`.

**Related work:** not scheduled

## Local functions and captures

**Status:** Partial.

Named local functions support direct calls, recursion, nested declarations and `::local` references
with concrete admitted Guest signatures. They can appear in ordinary functions, lambdas and class
initializers. Omitted defaults follow the ordinary call-site rules, including defaults that read captures.

```kotlin
fun counter(seed: Int): () -> Int {
    var count = seed
    fun next(step: Int = 1): Int {
        count += step
        return count
    }
    return ::next
}
```

Direct calls pass immutable captures as hidden typed arguments without allocating a managed environment.
Mutable captured variables share the existing managed capture cells with enclosing code and lambdas.
Escaping references retain their captures after the enclosing function returns; separate invocations and
loop iterations retain separate cells. Local helpers forward captures needed by their callees or references.
References allocate ordinary managed closures and remain subject to heap and execution quotas.

Local functions with their own type parameters specialize inferred or explicit concrete arguments,
including all twelve primitive families, nullable values and admitted references. Local helpers inside
generic top-level functions also specialize their enclosing captures before layout; defaults and escaping
references retain the concrete types. Generic callers of these owners use the same lexical specialization.
Direct primitive calls stay unboxed; each invocation retains its own mutable capture cells.

```kotlin
fun main() {
    fun <T> twice(value: T, transform: (T) -> T): T =
        transform(transform(value))
    check(twice(3) { it + 1 } == 5)
}
```

Lexical and ordinary generic function variants share the existing 256-variant budget; lexical expansion
also has a depth limit of 64. Type-growing recursion reports `UNSUPPORTED_IR` rather than creating
unbounded code. Methods and custom accessors of concrete generic classes also receive independent
symbol-remapped bodies before capture layout. Named local helpers, lambdas and escaping references may
capture `this`, concrete parameters and locals, or shared mutable cells. Captured `this` observes later
field changes; capturing a parameter retains its value. Different method invocations retain independent
cells. Primary constructor bodies, backing-field initializers and `init` blocks of ordinary generic classes also
receive concrete lexical copies. Lambdas and named local helpers can retain constructor parameters, `this`,
and shared mutable locals; local helpers with their own type parameters specialize before capture layout.
Direct methods with their own non-reified type parameters are supported as described below. Local inline/suspend/reified declarations remain outside this extension.

```kotlin
class Holder<T>(var value: T) {
    fun reader(): () -> T {
        fun read(): T = value
        return ::read
    }
}
```

**Evidence:** `generic class captures preserve concrete owners and escaping callbacks`,
`generic class direct local captures retain scalar signatures without environments` and
`expanding generic class local recursion reports bounded target diagnostics` in `MinimalScriptLoweringTest`.
`testKotlinGenericClassCapturesVmConformance` executes
[`generic-class-captures/main.kt`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/resources/kotlin/generic-class-captures/main.kt):
all twelve primitive families, nullable and object values, local/member recursion, interface dispatch,
custom accessors, bound/unbound references and shared cells. A 64 KiB heap retains an older owner callback
across 200 allocations of 12 KiB arrays. IDE test `generic class captures resolve with source and metadata inputs`
checks both platform modes. Related work: [#762](https://github.com/CertifiedBadIdeas/Compukters/issues/762).

Put an expression-bodied local declaration and subsequent statements on separate lines:
the current K2 frontend can omit a statement following that declaration on the same line.
The direct static `Tasks.launch(::worker)` path still requires an admitted top-level worker; stored function
values follow the ordinary task callback rules.

**Evidence:** `MinimalScriptLoweringTest` tests `local functions preserve recursive captures escaping
references and defaults`, `direct immutable local captures require no managed environment` and
`local references lower without inline normalization` and
`unsupported local declaration shapes report target diagnostics`; `testKotlinLocalFunctionsVmConformance`
executes the deterministic artifact from
[`local-functions/main.kt`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/resources/kotlin/local-functions/main.kt).
`DiagnosticQueryTest` test `captured local functions resolve with source and metadata inputs` checks both
platform input modes.
`MinimalScriptLoweringTest` also tests `generic local functions preserve concrete lexical captures and
escaping references`, `generic local calls retain scalar signatures without managed allocation`,
`generic local references lower without inline normalization` and `expanding generic local recursion
reports bounded target diagnostics`. `testKotlinGenericLocalFunctionsVmConformance` executes
[`generic-local-functions/main.kt`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/resources/kotlin/generic-local-functions/main.kt).
`DiagnosticQueryTest` test `generic local functions resolve with source and metadata inputs` checks both
platform modes.

**Related work:** [#758](https://github.com/CertifiedBadIdeas/Compukters/issues/758), [#755](https://github.com/CertifiedBadIdeas/Compukters/issues/755).

## Transparent blocking across project calls

**Status:** Supported.

An ordinary Guest function may call another ordinary function and resume across an asynchronous host
capability without a coroutine calling convention.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
test `ordinary project call resumes transparently across host blocking`, and
[`kotlin_writer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs),
test `k2_ordinary_project_call_resumes_across_async_capability`.

## Default arguments

**Status:** Partial.

Direct calls to project top-level, member and extension functions evaluate defaults using the supported
Guest expression subset. Defaults can read earlier parameters and receivers, call helpers, construct
arrays/objects or capture earlier parameters in lambdas. Concrete generic specializations and inherited
member defaults use the same rules; overriding implementations still dispatch normally.

```kotlin
import kotlin.random.Random

fun roll(sides: Int = 6, random: Random = Random.Default): Int =
    random.nextInt(1, sides + 1)
```

Receivers evaluate once, followed by all explicit arguments in source order, then omitted defaults in
parameter order. Defaults run on each invocation and only for omitted arguments. Temporary parameter
bindings are scoped to default evaluation, including nested calls. Primary constructor defaults retain
these ordinary call-site rules.

A cycle that requires recursively expanding the same default at compile time reports `UNSUPPORTED_IR`;
recursive calls supplying explicit arguments remain supported. References may omit trailing defaults under
the adaptation rules below. Separately compiled platform APIs publish the existing constant `Int`, enum-entry,
null and array-receiver-size payloads; arbitrary source expressions do not cross bundle metadata.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `sound beep lowers deterministically to a blocking Boolean capability operation`, `string arrays support
copyOfRange and supported default arguments`, and `primary constructor defaults preserve argument order and
earlier parameters` paired with `testKotlinConstructorDefaultsVmConformance`, and `function defaults preserve
expression evaluation bindings and dispatch` paired with `testKotlinFunctionDefaultsVmConformance`;
[`ParameterInfoQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/ParameterInfoQueryTest.kt),
test `parameter info exposes a platform Int default`.

**Related work:** not scheduled

## Extension functions and overloads

**Status:** Partial.

K2 resolves project extensions and overloads by symbol, and same-named project functions do not impersonate
trusted intrinsics. Execution coverage is not comprehensive.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `same-named char array helper remains an ordinary project call` and `same-named guest function remains
an ordinary project call`, plus `same-named guest calls preserve their resolved targets for vm conformance`
paired with `testKotlinNamedCallsVmConformance`.

**Related work:** not scheduled

## Named and vararg arguments

**Status:** Partial.

Ordinary K2 argument binding works only when the resulting direct call stays in the admitted signature subset;
direct `arrayOf` and `listOf` varargs are specially lowered, while spread arrays are rejected.

**Related work:** not scheduled

## Generic functions, classes, and interfaces

**Status:** Partial.

Top-level `fun <T>` calls with inferred or explicit concrete arguments and invariant `class Cell<T>` style
declarations are specialized at compile time. Primary-constructor fields and direct methods use concrete
scalar or reference types; a non-null `Int` remains unboxed in both calls and fields. Platform library bodies
containing generic functions or generic classes are specialized in a consumer; generic class methods and
constructor fields retain concrete `Int` and reference layouts. Generic member bodies can call top-level
generic helpers that introduce further generic class instances; class and function dependencies are collected
until specialization is complete. Mixed modules retain precompiled ordinary functions alongside generic/inline
bodies; specialization can call a shared compiled private helper without publishing it as Kotlin API or
duplicating its implementation. Ordinary library bodies may materialize concrete generic classes and
interfaces, including those in return and parameter types. Consumers and dependent libraries reuse an
available trusted specialization, including its constructors, fields and methods, with one nominal owner.
Variants absent from dependencies remain source-specialized; inconsistent layouts, missing member exports and
ambiguous owners are rejected. Function-valued parameters of source-compiled generic functions use the
specialized element type, as exercised by the `Iterable<T>` predicate helpers. Concrete generic interfaces and
covariant result interfaces support the read-only `List<T>` contract. Inherited interface selection methods
retain their concrete owner and callback types when called through a concrete implementation or a parent
interface, including an intermediate generic interface. Overloads can share `firstOrNull`, strict selection
and filtering without default callback expressions.

**Evidence:** `generic interface selection defaults preserve inherited typed callbacks`, executed by
`testKotlinProviderDefaultsVmConformance` (short-circuiting, absence, typed filtering and strict failure).
Open and abstract generic classes with `Any` or interface parents can be specialized as concrete superclasses
of ordinary classes and companions; constructor delegation and inherited virtual methods retain the
specialization. Imported interfaces remain in the nominal parent list, so upcasts and type tests retain their
contract. Generic classes extending a class other than `Any` remain rejected.

**Evidence:** `canonical peripheral companion specializes shared typed queries`, executed by
`testKotlinPeripheralQueriesVmConformance`.

### Direct generic methods

Ordinary Guest classes and objects may declare final, non-suspending methods with their own non-reified
type parameters. Direct calls specialize inferred or explicit concrete method arguments together with the
concrete receiver's class arguments. Primitive parameters and results remain unboxed; nullable, reference,
callable and MFVC values retain their existing representations.

```kotlin
class Box<T>(var value: T) {
    fun <R> map(transform: (T) -> R): Box<R> = Box(transform(value))
}
fun main() { check(Box(3).map { it.toLong() }.value == 3L) }
```

Defaults may use earlier method parameters and the owner's fields, including returning callbacks that
capture these values. Local helpers and escaping callbacks receive concrete lexical bodies and independent
mutable cells. Receiver and explicit arguments evaluate before omitted defaults; captured owners observe
later field writes. Recursion with the same types reuses its variant; type-growing calls retain the existing
256 function-variant bound and report `UNSUPPORTED_IR` when it is exceeded.

Generic virtual/interface methods, overrides, inherited generic methods, generic method references,
member extensions and reified/suspending forms remain outside this stage. Binary generic method templates
are unavailable; the executable body must be admitted source. Methods on value-class receivers with their
own type parameters remain outside this support claim.

**Evidence:** `generic methods preserve concrete owner and method captures` and
`generic methods keep direct primitive calls unboxed`, executed by `testKotlinGenericMethodsVmConformance`
(including 64 KiB heap collection), plus `generic methods reject virtual references and expanding specialization`.
IDE test `generic methods resolve with source and metadata inputs` checks both declaration-input modes.
**Related work:** [#774](https://github.com/CertifiedBadIdeas/Compukters/issues/774).

### Generic value classes and limits

Concrete generic value-class fields and member methods specialize
through the same bounded model; generic interface bridges and precompiled producer/consumer layouts retain
their nominal identity.

**Evidence:** `multi field value classes preserve direct layouts nested calls and managed boundaries` and
`multi field value classes share canonical layouts across precompiled addon boundaries`, executed by
`testKotlinMfvcVmConformance`. Contravariance, reified parameters and value-class methods declaring their own type
parameters remain outside the subset. Direct `Any` values use the [supported primitive boxes](primitives.md).
Expansion is bounded to 256 function and 256 class variants per project compilation. Platform/addon library builds allow 1024 class variants so complete enum caches and read-only collection views can be exported; their function limit remains 256. Binary generic library templates
and runtime instantiation are absent.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `generic identity specializes primitive and reference calls`, `generic cell specializes field layout and
preserves aliases`, `generic interface dispatch retains concrete Int and reference types`, `list Int
covariance to Any preserves the list and boxes reads`, and `unsupported generic forms report source
diagnostics without artifacts`;
[`K2CompilerAdapterTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/K2CompilerAdapterTest.kt),
test `source library generic functions and classes specialize in consumer`; VM conformance tasks
`testKotlinGenericFunctionsVmConformance`, `testKotlinGenericCellVmConformance`, and
`testKotlinGenericLibraryVmConformance`. Concrete library reuse is covered by
[`LibrarySpecializationsTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-artifact/src/test/kotlin/ru/lazyhat/compukters/compiler/artifact/link/LibrarySpecializationsTest.kt)
and
[`CompuktersFir2IrPipelineTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2-engine/src/test/kotlin/ru/lazyhat/compukters/compiler/k2/engine/CompuktersFir2IrPipelineTest.kt).

**Related work:** #652, #656, #670, #676

## Lambdas, local functions, and function references

**Status:** Partial.

Function values using supported Guest parameter and result types are admitted in project function
signatures and locals: values may be passed, returned, stored, and invoked. Nullable callbacks use the
same concrete callable interface with nullable reference storage; they can be initialized or reset to `null`.
Backed class `val`/`var` properties also store and replace concrete callbacks under the
[function-valued property contract](objects.md#function-valued-properties). The compiler generates an
interface for each distinct concrete signature without a fixed arity cut-off; artifact and VM limits still
apply. A function value can also be passed as an argument to another function value. Nested lambdas may
capture values through multiple lexical scopes and remain callable after the enclosing invocation returns.
Unbound references to non-suspending Guest top-level functions can likewise be stored, passed, returned, and
invoked, including with an inferred `KFunction` type. Only invocation is supported; reflection operations are
not. Bound references to ordinary Guest instance methods also work: the receiver expression is evaluated once,
retained by the function value, and dispatched virtually or through its interface when invoked. Unbound
instance-method references (`Type::method`) accept the receiver as their first argument and use the same
runtime dispatch.

Nullable callbacks support `?.invoke()`, Elvis, null comparisons, stable smart casts and non-null
assertions. A safe invocation evaluates its receiver once and skips argument evaluation when absent;
`!!` throws a catchable `NullPointerException` for null. Nullable primitive results use the ordinary
nominal boxes. A safe Unit callback result can be retained as `Unit?`, using the canonical Unit singleton
or null. Non-null calls retain their scalar/Unit calling convention. Callback captures and generic local
owners follow the same typed capture-cell rules as non-null function values.

```kotlin
class Controller {
    var onUpdate: (() -> Unit)? = null
    fun update() { onUpdate?.invoke() }
}
```

**Evidence:** `nullable callbacks preserve safe invocation storage captures and specialization`,
`nullable callable declarations discover signatures without callable expressions`,
`nullable and nonnull callbacks share concrete interface identity`,
`nullable Unit callback results are ordinary nullable references` and
`nullable function signature variance remains a target diagnostic` in `MinimalScriptLoweringTest`.
`testKotlinNullableCallbacksVmConformance` executes
[`nullable-callbacks/main.kt`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/resources/kotlin/nullable-callbacks/main.kt),
including all twelve primitive signatures, generic captures and a 64 KiB heap pressure test.
IDE test `nullable callbacks resolve with source and metadata inputs` checks both platform modes.

An expected function type may omit trailing default parameters of non-generic top-level and instance
functions. For example, `fun roll(sides: Int = 6): Int = sides` can be referenced as
`val draw: () -> Int = ::roll`. Bound method references retain their receiver once; unbound references
keep the receiver as their first invocation argument. Defaults evaluate freshly on each invocation,
using earlier supplied values, inherited declarations and ordinary virtual/interface dispatch. Supported
concrete generic class receivers and value-class arguments/results retain their existing representation.
Platform references use the same published default payloads as direct calls, including array-size defaults.
These adapters reuse ordinary managed closures and call lowering. Generic function references, extension
references, erased stateless platform receivers and Unit/suspend/vararg conversions remain outside this
adaptation subset. Direct `Tasks.launch` reference restrictions remain unchanged; stored callable values
follow the existing task-launch rules.

**Evidence:** `default adapted function references preserve invocation defaults and receivers`, executed
by `testKotlinDefaultFunctionReferencesVmConformance`; `unrelated function reference adaptations remain
target diagnostics` and IDE `default adapted function references resolve with source and metadata inputs`.

References to supported Guest primary constructors (`::Type`) can be stored, passed,
returned, and invoked, including zero- and multi-argument constructors. An expected function type may omit
trailing constructor parameters with supported defaults. Each invocation evaluates those defaults and
constructs a fresh instance through the existing class layout. Each lambda evaluation creates an ordinary
managed closure object; lambdas may capture immutable scalar and reference values, and reference captures
preserve the original referent and aliasing. A captured local `var` with no assignments after its
initialization is stored directly in the closure, like a `val`. A reassigned captured local uses one ordinary
managed typed cell per dynamic variable instance, shared by the enclosing code and every sibling closure;
primitive payloads remain unboxed.

**Evidence:** `read-only captured vars avoid cells while reassigned vars retain them` in
`MinimalScriptLoweringTest`, and read-only scalar, wide, nullable, reference and mutable capture execution in
`testKotlinFunctionValuesVmConformance`. Concrete supported generic collection instances such as
`ArrayList<Item>` may be captured through `val` or `var`; shared cells follow later reassignment.

**Evidence:** `Iterable destination operations append preserve types and return identity` in
`MinimalScriptLoweringTest`, executed by `testKotlinDestinationVmConformance`. Programs can declare top-level
and extension `inline` functions in project or admitted source-library files. Supported non-suspend,
non-reified calls are expanded before closure discovery; concrete generic inline calls retain scalar types.
Local/non-local and nested returns, Unit/Nothing, nullable and wide results, default callbacks and class
initializers are covered by `GuestInlineIntegrationTest` and `testKotlinInlineBlocksVmConformance`. Direct
scalar-only callbacks emit no object allocations in the focused artifact test. Stored/noinline callbacks,
escaping crossinline wrappers, nested captures and shared mutable cells retain ordinary managed ownership.
Runtime-referenced inline definitions remain usable; unused templates are discarded. Canonical
platform/intrinsic calls remain atomic, including transparent suspension through an expanded callback.
Member/local, suspend and reified inline declarations, unavailable/unadmitted bodies and unresolved type
arguments outside a supported enclosing generic owner are rejected with TARGET diagnostics. Preflight rejects
recursive inline dependencies, depth above 64 and cumulative projected work above 1,000,000 units;
specialization allows at most 256 variants and 64 active levels. These are conservative compiler safety
bounds, not calibrated VM capacity budgets. Production worker and JVM-plugin entry tests exercise the shared
pass; function-values, generic-library and transparent-call VM scenarios cover its integration.
Callback-taking collection extensions are inline and admit non-local returns from direct lambdas. Ordinary
generic wrappers retain their concrete emitter specialization; stored callbacks retain managed ownership.
`Tasks.launch` accepts direct, stored, and returned `() -> Unit` values. A direct top-level
`Tasks.launch(::worker)` remains a static spawn without a closure allocation. Types unsupported elsewhere in
Guest Kotlin, property references, constructors outside the admitted Guest class subset,
other reference adaptations, and variance conversions between different function signatures remain unsupported.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `supported function values lower to managed closures and shared capture cells` (including nested
closures and shared mutable captures), `task launch accepts direct stored and returned Unit lambdas`,
`nullable callback arguments accept ordinary lambdas`, `direct top level ordinary task lowers to spawn and
join`, `task launch rejects unsupported local and bound references`, `function value variance conversion is
rejected before artifact publication`, `supported constructor references lower to ordinary function values`,
`default adapted constructor references lower to managed function values`; root tasks `testKotlinFunctionValuesVmConformance` and
`testKotlinAdaptedConstructorsVmConformance`.

**Related work:** [#760](https://github.com/CertifiedBadIdeas/Compukters/issues/760), [#627](https://github.com/CertifiedBadIdeas/Compukters/issues/627),
[#631](https://github.com/CertifiedBadIdeas/Compukters/issues/631),
[#628](https://github.com/CertifiedBadIdeas/Compukters/issues/628),
[#632](https://github.com/CertifiedBadIdeas/Compukters/issues/632),
[#633](https://github.com/CertifiedBadIdeas/Compukters/issues/633),
[#634](https://github.com/CertifiedBadIdeas/Compukters/issues/634),
[#635](https://github.com/CertifiedBadIdeas/Compukters/issues/635),
[#636](https://github.com/CertifiedBadIdeas/Compukters/issues/636),
[#644](https://github.com/CertifiedBadIdeas/Compukters/issues/644)

## Recursion

**Status:** Partial.

Direct calls and local function references support recursion within the VM call-depth and execution
quotas. Newly compiled programs declare a maximum of 64 active calls, including library calls;
this matches the native runtime profile. Required frame storage is calculated for that depth and
checked at admission, and exceeding the call-depth limit still traps. `testKotlinLocalFunctionsVmConformance` exercises recursive local calls and reference invocation;
other recursive forms retain their surrounding signature and dispatch restrictions.

**Related work:** not scheduled

## Top-level state

**Status:** Partial.

Immutable top-level properties support direct `Int`, `Long`, `Float`, `Double`, `Boolean`, `Char`, and
`String` literals and supported nullable reference `null` initializers. They lower to lazily initialized static
VM storage. Top-level `var`, custom or delegated accessors, initializer dependencies, and arbitrary object
construction remain unsupported.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
test `direct top level ordinary task lowers to spawn and join`, executed by
`testKotlinTasksVmConformance`, also checks scalar top-level state without admitting channel storage.

**Related work:** [#702](https://github.com/CertifiedBadIdeas/Compukters/issues/702)

Value-class method and constructor references, typed callbacks and capture storage follow the [value-class
contract]({{ '/KOTLIN-SUPPORT/objects/' | relative_url }}).

## Compile-time contracts

**Status:** Supported for classic Kotlin effects.

Guest code can use `kotlin.contracts.contract` at the beginning of a function body, with
`@OptIn(ExperimentalContracts::class)` on a declaration or file. Supported effects are
`callsInPlace` with `EXACTLY_ONCE`, `AT_MOST_ONCE`, `AT_LEAST_ONCE` or `UNKNOWN`, `returns()`,
`returns(true/false/null)`, `returnsNotNull()` and `SimpleEffect.implies`. K2 validates the
restricted conditions (parameter/receiver null and type checks, Boolean parameters and logical
combinations), placement and legal uses. Extended effects such as `holdsIn`, reverse `implies`
and `returnsResultOf` are unavailable.

`EXACTLY_ONCE` enables definite assignment of local `val` and `var` inside a synchronous callback;
`AT_LEAST_ONCE` can initialize a `var`, while `AT_MOST_ONCE` alone does not establish initialization.
A nullable `var` assigned a non-null value inside a callback does not thereby acquire a non-null
smart cast after the call. Prefer a non-null local declared without an initializer when the block
establishes its value. Conditional return effects support separate null/type smart casts.

K2 serializes contract effects into native platform metadata. Contract descriptions and their DSL
are compile-time declarations: they produce no runtime builder, effect objects, calls or VM
instructions. Annotation class tokens do not provide runtime reflection. Locals without initializers
retain K2's definite-assignment checks; captured assignments use existing managed cells. Uninitialized
reference cells have nullable storage, and reads recover the declared type. Read-only locals assigned
before a closure is created retain ordinary capture-by-value behavior.

Contracts are promises by a function author, not a proof that arbitrary implementations keep them and
not a host safety boundary. A calls-in-place promise excludes invoking or retaining the callback after
the function returns. Deferred callbacks such as `onClick` and `onDraw` have no such promise. Host
validation, artifact verification and VM resource limits remain authoritative.

**Evidence:** `MinimalScriptLoweringTest`, tests `classic contracts preserve initialization conditional
effects and compile time erasure`, `contract descriptions emit the same executable instructions as plain
functions` and `invalid contract effects and insufficient initialization guarantees are rejected`;
`testKotlinContractsVmConformance`. Metadata-only declarations/defaults are covered by
`PlatformMetadataCompilerTest`, test `compile time contracts and annotation defaults remain metadata only`.

Platform effects on scope functions, preconditions, `buildString`, display `frame` and UI construction
are available without opting in at call sites. `DiagnosticQueryTest`, test `classic contracts and platform
construction effects resolve with source and metadata inputs`, covers IDE diagnostics with and without
attached SDK sources. Nested UI initialization executes in `testKotlinDisplayUiVmConformance`.
