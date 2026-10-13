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
Addon modules retain ordinary executable methods as precompiled imports alongside source value layouts.
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
A concrete method declared in a final class uses a direct call when the target is statically known, including
bound and unbound method references. Its virtual declaration remains available to calls through parent classes
and interfaces. Inherited fake overrides retain their existing dispatch path. Nullable receiver checks and
exceptions from reading an uninitialized non-null field still precede method execution.
Final ordinary class/object methods with their own type parameters support direct concrete calls; see
[direct generic methods](functions.md#direct-generic-methods). Generic virtual/interface methods, generic
method references, suspending methods and member extensions remain unsupported. Interface methods may have supported
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

## Function-valued properties

**Status:** Supported for concrete callable signatures with nullable or non-null storage.

Guest classes can store callbacks in backed `val` and `var` properties, initialize them from constructor
arguments or body expressions, read them through aliases, replace mutable callbacks and invoke them.
Nullable callback properties can default to `null`, be reset, and invoked safely through `?.invoke()`.
Custom and inherited/interface accessors retain ordinary property dispatch. Concrete generic owners
specialize callback parameter/result types, including scalar and Guest object types. Constructor defaults
can build callbacks that capture earlier constructor parameters.

```kotlin
class Counter(val next: () -> Int)

fun counter(): Counter {
    var count = 0
    fun next(): Int {
        count += 1
        return count
    }
    return Counter(::next)
}
```

Fields hold managed references to the existing callable interfaces. Stored callbacks retain captured
objects and shared mutable cells after their enclosing function returns. Replacing a callback preserves
any separately retained old reference; unreachable closures follow ordinary managed heap collection.
Calling a property evaluates its receiver once. Call arguments use the signature's expected types,
including nullable parameter types. Nullable and non-null references share the same concrete callable
interface. Safe invocation skips argument effects for null; separately retained aliases continue to call
the original closure after a field is reset. Unreachable captures are reclaimed by ordinary managed GC,
not immediately on reset. Conversions between different callable signatures remain unsupported.

**Evidence:** `MinimalScriptLoweringTest` tests `function fields preserve callback ownership replacement
accessors and specialization`, `function fields discover signatures without callable expressions` and
`variant function fields report target diagnostics`;
`testKotlinFunctionFieldsVmConformance` executes
[`function-fields/main.kt`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/resources/kotlin/function-fields/main.kt).
`DiagnosticQueryTest` test `function fields resolve with source and metadata inputs` covers both platform input modes.

`testKotlinNullableCallbacksVmConformance` also exercises nullable constructor defaults, generic fields,
custom and interface accessors, reset/alias behavior and repeated captured-array replacement on a 64 KiB
heap. IDE `nullable callbacks resolve with source and metadata inputs` covers both platform input modes.

**Related work:** [#760](https://github.com/CertifiedBadIdeas/Compukters/issues/760), [#757](https://github.com/CertifiedBadIdeas/Compukters/issues/757).

Generic class methods and custom accessors can return callbacks and local function references with
concrete owner capture layouts. Capturing `this` keeps the same owner alive and observes field mutations;
captured method locals follow the ordinary shared-cell rules. See [local functions](functions.md#local-functions-and-captures)
and `testKotlinGenericClassCapturesVmConformance` for executed evidence. Primary constructor bodies, backing-field
initializers and init blocks of ordinary generic classes also support lexical captures as described below.

## Captures during generic class initialization

**Status:** Supported for ordinary classes in the existing generic class subset.

Primary constructor bodies, initialized backing fields and `init` blocks specialize their lexical symbols
and types together for each concrete owner. Lambdas and named local helpers can capture constructor
parameters, `this`, and shared mutable locals. Local generic helpers specialize before capture layout;
direct calls and escaping references use the existing closure and capture-cell representation.

```kotlin
class Holder<T>(initial: T) {
    var value: T = initial
    val snapshot: () -> T = { initial }
    val read: () -> T = { value }
}
```

`snapshot` retains the parameter value; `read` retains the same owner and observes later field writes.
Different instances and concrete type specializations keep separate capture cells. Constructor callback
defaults can capture earlier parameters through the same concrete initialization copy. Constructor
arguments evaluate once in source order; properties and `init` blocks execute in declaration order.
Existing field-initialization rules still apply: this extension does not admit uninitialized body `val`
properties assigned later in `init`, secondary constructors, generic superclass inheritance, or lexical
captures in value-class initialization.

Captured owners and values remain live under ordinary managed GC. Each materialized closure/cell uses
the existing metered heap allocations; initialization adds no special runtime storage or VM instruction.
Concrete dependencies use the existing 256-variant bounds and lexical expansion depth limit.

**Evidence:** `MinimalScriptLoweringTest`, test `generic initialization captures preserve concrete symbols
order and ownership`, with deterministic artifact checking, and `testKotlinGenericInitializerCapturesVmConformance`
execute [`generic-initializer-captures/main.kt`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/resources/kotlin/generic-initializer-captures/main.kt).
The scenario covers all twelve primitive families, nullable/reference/MFVC values, parameter versus owner
captures, local generic helpers and defaults, independent mutable cells, recursive construction, nested
generic dependencies, explicit argument snapshots, declaration order and retained callbacks across 200 allocations of 12 KiB arrays
on a 64 KiB heap. IDE test `generic initialization captures resolve with source and metadata inputs`
checks both platform input modes. Compiler test `expanding generic initialization reports bounded target diagnostics`
checks that type-growing recursive construction stops before publishing an artifact.

**Related work:** [#769](https://github.com/CertifiedBadIdeas/Compukters/issues/769).

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

Stateless enums also expose cached, typed `EnumType.entries`, including empty enums and separately
compiled library enums. The [collection contract](../stdlib/collections.md#immutable-enum-entries)
owns its immutable list operations and executable evidence. This does not add `values()`, `valueOf()`,
`name`, `ordinal` or `compareTo()` execution support. The structural `Enum.compareTo` declaration keeps
IDE enum inheritance complete without claiming those runtime operations.

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
these identities, including concrete generic value classes. Reading a value-class property from an imported
library object loads its nominal field box and recovers the inline layout; Path-valued compilation results
exercise this boundary in `typed compiler facade lowers Path results and rejects removed APIs` and
`ProgramRuntimeHostIntegrationTest.typed Guest compilation and process paths preserve diagnostics permissions and outputs`. Single `Int`/`Boolean`/`Char` peripheral handles
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
canonical identity across precompiled addon functions`. `testKotlinSubsetVmConformance` also verifies that
string interpolation of `Redstone.left` renders the nominal `RedstoneSide(index=2)` value rather than its
underlying scalar. Existing Guest boundaries still apply: secondary
constructors, runtime generic instantiation and value-class methods declaring their own type parameters are outside the
subset. `@JvmInline` is deliberately rejected as JVM syntax.

**Related work:** [#701](https://github.com/CertifiedBadIdeas/Compukters/issues/701).

See [value text and hashing]({{ '/STDLIB-SUPPORT/core-text/#value-text-and-hashing' | relative_url }}) for
`toString`, `hashCode`, and generated-property rules.

### Value-class initializer dependencies

Value-class initializers retain their literal pool entries and admitted platform calls, including exception
constructors. This applies to direct construction and constructor references. Execution evidence:
`testKotlinGeometryVmConformance` exercises source and precompiled rectangle validation; the focused
`MinimalScriptLoweringTest` case `value class initializer literals are retained without an ordinary constructor body`
protects initializer-only strings and exception imports.


Platform libraries export mutable property accessors, including custom setter validation and
side effects. Private setters remain inaccessible from external Kotlin source. `PlatformMetadataCompilerTest` exercises setter
metadata; `testKotlinDisplayUiVmConformance` executes the validated slider setter and viewport setter
cancellation through the compiled Guest library.
