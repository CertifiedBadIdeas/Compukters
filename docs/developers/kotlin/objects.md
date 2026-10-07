---
layout: default
title: Classes, interfaces and value classes
section: developers
permalink: /KOTLIN-SUPPORT/objects/
---

# Classes, interfaces and value classes

[← Guest Kotlin support]({{ '/KOTLIN-SUPPORT/' | relative_url }})

* On this page
{:toc}

This page describes the current checkout, including unreleased work. [Status and evidence policy]({{ '/KOTLIN-SUPPORT/#status-and-evidence' | relative_url }}) applies to every entry.

## Value equality and virtual `equals` for supported Guest values

**Status:** Supported.

`==`, `!=` and explicit `equals` share ordinary virtual `Any.equals(Any?)` for references. Null left receivers
use null equality; a literal-null comparison skips overrides, while explicit `equals(null)` invokes the
receiver. Operands evaluate once in order. String compares UTF-16 content; typed scalar boxes compare values,
with canonical NaN and distinct signed zero for Float. Primitive Float `==` retains IEEE equality. Objects and
arrays default to identity; custom and inherited overrides, `super.equals`, exceptions and independently
compiled library methods use normal dispatch. Generated data-class methods compare constructor properties,
including nullable, Float and object fields; arrays compare identity and body properties are excluded. Hashes
agree for supported equal values; custom classes remain responsible for coherent equals/hashCode overrides.
Other generated data/enum methods remain partial.

**Evidence:** `virtual equals handles libraries data values nullable references and effects for vm execution`
(`testKotlinEqualsVmConformance`, 64 KiB heap and repeated allocation), `source library generic functions and
classes specialize in consumer` (`testKotlinGenericLibraryVmConformance`), `testKotlinHashCodeVmConformance`,
`testKotlinListAnyVmConformance` and IDE test `toString and Any lazy assertion messages share canonical IDE
semantics` with equals diagnostics/completion. Reuses Runtime ABI 1.11; compiled libraries require rebuilding
against the canonical runtime module.

**Related work:** [#685](https://github.com/CertifiedBadIdeas/Compukters/issues/685)

## Named singleton objects and companions

**Status:** Supported.

Project `object` and `companion object` declarations lower to managed classes with one lazily initialized
static instance. Supported field initialization, methods, identity and interface dispatch share that instance.
A companion can implement a concrete generic interface, including inherited methods with typed predicates.
Addon objects implementing interfaces export their instance and nominal type; selected addon source templates
specialize through the same boundary as base library templates. Existing platform namespace objects without
supertypes retain their static facade representation.

**Evidence:** `singleton companions retain identity state and generic provider dispatch` and `addon companion
provider imports one singleton and inherited generic methods`, executed by
`testKotlinSingletonProvidersVmConformance`. The addon test includes an unrelated callback shape to verify
that callback identities depend on their signatures rather than discovery order. Anonymous object expressions
remain outside this support claim.

## Instance methods and dynamic dispatch

**Status:** Partial.

Supported Guest classes may declare ordinary non-suspending methods, override class methods, and implement
abstract interface methods. Calls through class and interface references select the runtime implementation.
Generic or suspending methods, member extensions remain unsupported. Interface methods may have supported
non-suspending bodies; an inherited default uses the most specific interface declaration unless a class
overrides it. An override may call a concrete interface body with `super<Interface>`, including one inherited
through that interface; the call bypasses dynamic dispatch to the override.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `guest instance methods lower with deterministic owners flags and method ranges` and `guest instance
methods reject unsupported callable shapes`, paired with the `testKotlinDispatchVmConformance` Kotlin-to-VM
execution gate, and test `interface defaults lower to methods and computed accessors` paired with
`testKotlinInterfaceDefaultsVmConformance`, and test `qualified interface super calls use direct default
bodies` paired with `testKotlinInterfaceSuperVmConformance`.

**Related work:** [#626](https://github.com/CertifiedBadIdeas/Compukters/issues/626)
[#641](https://github.com/CertifiedBadIdeas/Compukters/issues/641), and
[#642](https://github.com/CertifiedBadIdeas/Compukters/issues/642).

## Guest class initialization

**Status:** Partial.

Supported primary constructors initialize backed `val` and `var` properties, class-body properties, and `init`
blocks in source order after superclass initialization on the same managed object. Plain constructor
parameters may be used without becoming fields. Mutable properties with default accessors can be read and
assigned through aliases and instance methods; each object retains its own field state. Primary-constructor
defaults can refer to earlier parameters and use supported Guest expressions. Explicit arguments evaluate in
call-site order, followed by omitted defaults in parameter order; the constructor receives the complete values
before its property and `init` work. Constructor references retain their declared full arity unless an
expected function type selects supported trailing defaults. Secondary constructors remain unsupported.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
test `guest object subset lowers sealed results data values enum identity and type branches`, paired with
[`heap_tests.rs`](https://github.com/CertifiedBadIdeas/Compukter-VM/blob/main/src/execution/heap_tests.rs),
tests `heap_instructions_round_trip_reference_fields` and
`heap_instructions_use_inherited_fields_and_interface_closure`, plus
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `mutable constructor properties lower to instance field writes` and `immutable constructor property
assignment remains rejected`, paired with root task `testKotlinMutableFieldsVmConformance`, and test `class
body properties and init blocks lower in construction order` paired with
`testKotlinClassInitializationVmConformance`, and test `primary constructor defaults preserve argument order
and earlier parameters` paired with `testKotlinConstructorDefaultsVmConformance`, and test `default adapted
constructor references lower to managed function values` paired with
`testKotlinAdaptedConstructorsVmConformance`.

**Related work:** [#637](https://github.com/CertifiedBadIdeas/Compukters/issues/637),
[#638](https://github.com/CertifiedBadIdeas/Compukters/issues/638),
[#643](https://github.com/CertifiedBadIdeas/Compukters/issues/643), and
[#644](https://github.com/CertifiedBadIdeas/Compukters/issues/644).

## Class property accessors

**Status:** Partial.

Class `val` and `var` properties support computed getters and source-defined non-suspending getters/setters.
Accessors use ordinary instance-method dispatch, including overrides called through a base-class reference;
`field` reads and writes use the property's managed backing field when present. Non-overriding final default
accessors retain direct field access. Abstract `val` and `var` declarations in classes and interfaces
contribute accessor methods without allocating fields; calls through either base type select a concrete backed
or computed implementation. Interface properties can also define computed getter and setter bodies; interface
backing fields, top-level custom accessors, delegated properties, and unsupported Guest types remain outside
this subset.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
test `computed and custom class accessors lower with backing fields and override dispatch`, paired with root
task `testKotlinPropertyAccessorsVmConformance`, and test `abstract class and interface properties lower to
dispatched accessors without fields` paired with `testKotlinAbstractPropertiesVmConformance`, and test
`interface defaults lower to methods and computed accessors` paired with
`testKotlinInterfaceDefaultsVmConformance`. Qualified `super<Interface>` getter and setter calls are covered
by `qualified interface super calls use direct default bodies` and `testKotlinInterfaceSuperVmConformance`.

**Related work:** [#639](https://github.com/CertifiedBadIdeas/Compukters/issues/639),
[#640](https://github.com/CertifiedBadIdeas/Compukters/issues/640), and
[#641](https://github.com/CertifiedBadIdeas/Compukters/issues/641), and
[#642](https://github.com/CertifiedBadIdeas/Compukters/issues/642).

## Sealed interfaces, data classes, and stateless enums

**Status:** Partial.

The admitted fixture lowers sealed result types, immutable data values, enum identity, exhaustive type
branches, and smart-cast property reads, then executes those branches in the pinned VM. This does not imply
support for all generated data or enum methods. Value-producing exhaustive `when` supports reference results
without a source `else`; its synthesized impossible branch throws `NoWhenBranchMatchedException`, never a
dummy value.

**Evidence:** `assertions and exhaustive reference when share exceptions for vm execution`, executed by
`testKotlinExceptionsVmConformance` for Boolean and enum subjects returning String. The bundled `boot` keeps
its explicit diagnostic fallback, exercised by `checked in boot compiles deterministically with process
intrinsic` and `:core:programRuntimeIntegrationTest`.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
test `guest object subset lowers sealed results data values enum identity and type branches`, paired with root
task `testKotlinObjectModelVmConformance`.

**Related work:** not scheduled

## Secondary constructors and stateful enums

**Status:** Unsupported.

These shapes are rejected before artifact publication.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
test `guest object subset rejects generic secondary uninitialized and stateful shapes`.

**Related work:** not scheduled

## Type tests and casts

**Status:** Partial.

`is` checks and compiler-generated smart casts over admitted references lower to VM type checks and checked
casts. Boxed `Int` values support `is Int`, `is Int?`, and explicit checked `as Int` / `as Int?`. Checked
casts between admitted reference types are supported. Safe casts (`as?`) are exercised for imported value
classes by `testKotlinMfvcVmConformance`. The sealed-reference fixture in
`testKotlinObjectModelVmConformance` checks successful, incompatible and null casts followed by safe calls;
this does not establish every reference-cast shape. Nullable type
tests admit null. Reference `===`/`!==` compare identity directly, including null, without artificial `Any`
casts. Supported arrays preserve identity across aliases and differ from fresh copies, including comparisons
between arrays with nullable and non-null element types. Operands evaluate left-to-right once. Heterogeneous
reference comparison requires Runtime ABI 1.6; existing same-nominal comparisons retain older ABI
requirements.

**Evidence:** `testKotlinNullableCollectionsVmConformance` and
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `guest object subset lowers sealed results data values enum identity and type branches` and `guest
object subset rejects generic secondary uninitialized stateful and explicit cast shapes`, paired with
[`heap_tests.rs`](https://github.com/CertifiedBadIdeas/Compukter-VM/blob/main/src/execution/heap_tests.rs),
test `heap_instructions_checked_cast_handles_nullability_and_incompatibility`. Array identity evidence:
`testKotlinIntArrayVmConformance`, test `specialized IntArray lowers deterministically for vm conformance`,
and native test `reference_identity_compares_typed_arrays_and_null_without_casts`. Array identity tracking:
[#680](https://github.com/CertifiedBadIdeas/Compukters/issues/680). Supported arrays can pass through
`Any`/`Any?`, fields, function parameters and concrete generic calls without boxing or copying; reverse
checked casts and type tests retain the dynamic array type. Explicit array root metadata requires Runtime ABI
1.7.

**Evidence:** `testKotlinIntArrayVmConformance`, test `specialized IntArray lowers deterministically for vm
conformance`, native test `array_root_casts_preserve_identity_and_dynamic_type`, and artifact test `array
superclass round trips and requires ABI 1_7 and stateless root`.

**Related work:** [#681](https://github.com/CertifiedBadIdeas/Compukters/issues/681). Remaining type-test/cast
support: not scheduled.

## Value-class layouts and existing Guest use sites

**Status:** Supported.

One or multiple immutable constructor properties can contain all twelve primitive types, supported references
including nullable references, nested value classes and concrete generic substitutions. Direct locals,
assignment, control flow, operators, methods, parameters, returns and typed callbacks preserve the nominal
inline layout in compact frames. Generic member methods, interface bridges, destructuring, bound/unbound
method and constructor references use the same layouts. Constructor arguments evaluate once in source order;
constructor references also execute initializers and their checks. Cyclic inline layouts and mutable payloads
produce diagnostics. `Any`, nullable values, interfaces, ordinary fields, reference arrays, `List`,
`MutableList`, `ArrayList`, lambda captures and child-task captures use nominal managed boxes. Reads recover
the direct layout. Equality/hash/text are structural and nominal, including NaN and signed-zero behavior,
unsigned formatting, nullable fields, nested values and source `toString` overrides. Precompiled
platform/addon libraries export a canonical direct layout, wrapper and all payload fields; a consumer shares
these identities, including concrete generic value classes. Single `Int`/`Boolean`/`Char` peripheral handles
retain their established scalar ABI fast path. New inline layouts require Runtime ABI 1.15. The Guest frontend
and native IDE share K2's `JvmInlineMultiFieldValueClasses` frontend setting and retain
`MultiFieldValueClassRepresentation`. This switch permits the shared frontend checker; it does not select
a JVM backend. No JVM annotations are needed. Execution evidence: `MinimalScriptLoweringTest`, tests `multi field value classes
preserve direct layouts nested calls and managed boundaries` and `multi field value classes share canonical
layouts across precompiled addon boundaries`, both executed by `testKotlinMfvcVmConformance`. Rejection
evidence: `multi field value classes reject cyclic layouts and mutable payloads`. Frontend/library evidence:
`CompuktersFir2IrPipelineTest`, test `multi field value classes resolve on Guest platform and retain their IR
representation`. IDE evidence: `DiagnosticQueryTest`, test `native MFVC analysis preserves source and addon
nominal types and members`, with and without attached library sources; checks diagnostics, nominal expression
types and member completion. Scalar regression evidence: `testKotlinValueClassBoxesVmConformance` executes
`value class boxes preserve nominal types nullable collections and iteration` and `value class boxes share
canonical identity across precompiled addon functions`. Existing Guest boundaries still apply: secondary
constructors, runtime generic instantiation and methods declaring their own type parameters are outside the
subset. `@JvmInline` is deliberately rejected as JVM syntax.

**Related work:** [#701](https://github.com/CertifiedBadIdeas/Compukters/issues/701).

See [value text and hashing]({{ '/STDLIB-SUPPORT/core-text/#value-text-and-hashing' | relative_url }}) for
`toString`, `hashCode`, and generated-property rules.
