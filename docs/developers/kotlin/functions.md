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

Platform APIs may publish constant `Int` or qualified enum-entry defaults, which direct platform calls lower
without JVM mask dispatchers. Omitted `Array<String>` parameters in project functions are supported only for
direct `emptyArray()` or direct `arrayOf` call defaults. Primary constructors evaluate supported Guest default
expressions at ordinary call sites after explicit arguments; general project function defaults remain limited
to the documented forms.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `sound beep lowers deterministically to a blocking Boolean capability operation`, `string arrays support
copyOfRange and supported default arguments`, and `primary constructor defaults preserve argument order and
earlier parameters` paired with `testKotlinConstructorDefaultsVmConformance`;
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
`testKotlinPeripheralQueriesVmConformance`. ### Generic value classes and limits

Concrete generic value-class fields and member methods specialize
through the same bounded model; generic interface bridges and precompiled producer/consumer layouts retain
their nominal identity.

**Evidence:** `multi field value classes preserve direct layouts nested calls and managed boundaries` and
`multi field value classes share canonical layouts across precompiled addon boundaries`, executed by
`testKotlinMfvcVmConformance`. Contravariance, reified parameters and generic methods declaring their own type
parameters remain outside the subset. Direct `Any` values use the [supported primitive boxes](primitives.md).
Expansion is bounded to 256 function and 256 class variants per compilation. Binary generic library templates
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

Non-null function values using supported Guest parameter and result types are admitted in project function
signatures and locals: values may be passed, returned, stored, and invoked. The compiler generates an
interface for each distinct concrete signature without a fixed arity cut-off; artifact and VM limits still
apply. A function value can also be passed as an argument to another function value. Nested lambdas may
capture values through multiple lexical scopes and remain callable after the enclosing invocation returns.
Unbound references to non-suspending Guest top-level functions can likewise be stored, passed, returned, and
invoked, including with an inferred `KFunction` type. Only invocation is supported; reflection operations are
not. Bound references to ordinary Guest instance methods also work: the receiver expression is evaluated once,
retained by the function value, and dispatched virtually or through its interface when invoked. Unbound
instance-method references (`Type::method`) accept the receiver as their first argument and use the same
runtime dispatch. References to supported Guest primary constructors (`::Type`) can be stored, passed,
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
Guest Kotlin, local-function and property references, constructors outside the admitted Guest class subset,
other adapted references, and variance conversions between different function signatures remain unsupported.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `supported function values lower to managed closures and shared capture cells` (including nested
closures and shared mutable captures), `task launch accepts direct stored and returned Unit lambdas`,
`unsupported closure shapes produce stable diagnostics`, `direct top level ordinary task lowers to spawn and
join`, `task launch rejects unsupported local and bound references`, `function value variance conversion is
rejected before artifact publication`, `supported constructor references lower to ordinary function values`,
`default adapted constructor references lower to managed function values`, and `unsupported constructor
reference is rejected before artifact publication`; root tasks `testKotlinFunctionValuesVmConformance` and
`testKotlinAdaptedConstructorsVmConformance`.

**Related work:** [#627](https://github.com/CertifiedBadIdeas/Compukters/issues/627),
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

Direct calls and bounded VM call depth can represent recursion, but no Kotlin-to-VM recursive source
conformance test defines it as a supported language contract.

**Related work:** not scheduled

## Top-level state

**Status:** Partial.

Immutable top-level properties support direct `Int`, `Long`, `Float`, `Double`, `Boolean`, `Char`, and
`String` literals plus direct `IntChannel(capacity)` construction. They lower to lazily initialized static VM
storage. Top-level `var`, custom or delegated accessors, initializer dependencies, and arbitrary object
construction remain unsupported.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `top level IntChannel lowers to VM owned bounded handoff` and `IntChannel construction rejects
unsupported ownership and capacity`.

**Related work:** [#614](https://github.com/CertifiedBadIdeas/Compukters/issues/614)

Value-class method and constructor references, typed callbacks and capture storage follow the [value-class
contract]({{ '/KOTLIN-SUPPORT/objects/' | relative_url }}).
