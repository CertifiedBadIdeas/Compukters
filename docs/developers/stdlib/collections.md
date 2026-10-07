---
layout: default
title: Collections and iterable operations
section: developers
permalink: /STDLIB-SUPPORT/collections/
---

# Collections and iterable operations

[← Guest standard library support]({{ '/STDLIB-SUPPORT/' | relative_url }})

* On this page
{:toc}

This is an executable API inventory for the current checkout. [Status and evidence policy]({{ '/STDLIB-SUPPORT/#status-and-evidence' | relative_url }}) applies to every entry. Signatures are indexed in
the [generated Guest API reference]({{ '/guest-api/' | relative_url }}).

## Lists and selection

### `Iterable<T>` search and predicate operations

**Status:** Supported.

`contains` / `in`, `indexOf`, and `lastIndexOf` traverse any iterable, including a user-defined one. `any`,
`all`, and `none` use `iterator()` and stop when the result is known. Callback overloads are inline and admit
non-local returns. Empty iterables return `false`, `true`, and `true`, respectively. These operations work
through `Iterable<Int>` and `Iterable<Any>` references.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
test `list Int covariance to Any preserves the list and boxes reads`, executed by
`testKotlinListAnyVmConformance`.

### Strict first-element selection

**Status:** Supported.

`Iterable<T>.first`, with or without a typed predicate, returns the first selected element, including null for
nullable element types. Empty or unmatched selection throws `NoSuchElementException`, a normal Guest subclass
of `RuntimeException`. Predicates stop at the first match; inline predicates support non-local returns.
`find(predicate)` aliases `firstOrNull(predicate)`. Available in `stdlib:core` 1.8.0 without a Runtime ABI
change.

**Evidence:** `collection first preserves nullable values and throws only on absence`, executed by
`testKotlinProviderDefaultsVmConformance`.

### Nullable element selection

**Status:** Supported.

`Iterable<T>.firstOrNull` and `lastOrNull`, with or without a predicate, return the selected element or null
when empty or unmatched. `firstOrNull(predicate)` stops at the first match; `Iterable.lastOrNull(predicate)`
traverses forward to the end. List overloads read first/last elements by index; `List.lastOrNull(predicate)`
searches backwards and stops at the first match from the end. Predicate overloads are inline and admit
non-local returns from direct lambdas. `List<T>.getOrNull(index)` returns null for negative or out-of-range
indexes. Results from `Int` elements are `Int?`; nullable elements retain their stored references, including
Int boxes. These are imported `kotlin.collections` extensions.

**Evidence:** `MinimalScriptLoweringTest`, test `collection nullable selection preserves traversal values and
identity`, executed by `testKotlinCollectionSelectionVmConformance` with bounded slices.

### Iterable accumulation

**Status:** Supported.

`Iterable<T>.fold(initial: R, operation: (R, T) -> R)` accumulates in iteration order, calling the operation
once per element. Empty iterables return the initial value unchanged. Element and accumulator types specialize
independently, including nullable references, `Int?`, and supported Guest classes. Generic callers can forward
the operation with their instantiated types. Like other Guest higher-order helpers, callback overloads are
inline and support non-local returns from direct lambdas.

**Evidence:** `MinimalScriptLoweringTest`, test `Iterable fold specializes independent element and accumulator
types`, executed by `testKotlinFoldVmConformance` with bounded slices.

### Read-only `Collection<T>` and `List<T>`

**Status:** Partial.

Direct `listOf(...)`, `listOf<T>()`, and `emptyList<T>()` support all twelve primitives, `String`, supported
value classes and supported Guest class references, including nullable elements. `size`, indexed `get`, and
ordinary `for` iteration execute through specialized `ArrayList<T>` instances. Factories create fresh lists
with capacity equal to their element count; the `List<T>` view can be cast to `MutableList<T>` or
`ArrayList<T>` to change the same object. `List<Int>` stores and returns unboxed i32 values; list aliases
refer to the same object. Factory arguments run once in source order. Invalid indexes throw catchable
`IllegalArgumentException` through the shared `ArrayList` argument check; iteration resumes across quota
slices. `List<Int>` can widen to `List<Any>` without copying the list; reads and iteration through the
universal view allocate `Int` boxes with checked `is Int` and `as Int` access. A supported reference list can
also widen to `List<Any>` while preserving its element references. Direct `listOf<Any>(...)` stores supported
scalar boxes and references in one array; indexed reads reuse those references. `Collection<T>` owns `size`,
`isEmpty`, and `contains`; custom collections can implement this contract. `isNotEmpty` is a `Collection<T>`
extension. `List<T>` owns indexed access and the index-based `indexOf` and `lastIndexOf` methods; custom lists
implement them. Searches use the supported `==` semantics and return the first or last match, or `-1`. The
`Iterable<T>` search and predicate extensions above also work on these lists. `List<Int?>` stores managed Int
boxes or null. Nullable lists widen to `List<Any?>` without copying; reads and searches preserve null and use
value equality. Non-null lists also widen to `List<Any?>`.

**Evidence:** `testKotlinNullableCollectionsVmConformance`, test `nullable Int and collection elements
preserve values and nulls`. Extension functions are Guest `kotlin.collections` declarations and require
imports. Spread arguments, `Set`, `Map`, sequences, and collection APIs not listed here remain unavailable.
Generic list implementations are distributed as source bodies in the hybrid `stdlib:core` module, alongside
ordinary precompiled library implementations.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `read only lists retain typed Int String and guest references`, `list index outside bounds compiles to
trapped array access`, `list iterator resumes across quota slices`, `list Int covariance to Any preserves the
list and boxes reads`, and `unsupported list element and spread forms report diagnostics`; VM tasks
`testKotlinListVmConformance`, `testKotlinListAnyVmConformance`, `testKotlinListAnyQuotaVmConformance`,
`testKotlinListBoundsVmConformance`, and `testKotlinListQuotaVmConformance`.

**Related work:** [#656](https://github.com/CertifiedBadIdeas/Compukters/issues/656),
[#581](https://github.com/CertifiedBadIdeas/Compukters/issues/581)

## Mutation and transformation

### Primitive list specializations

**Status:** Supported.

`List<T>`, `MutableList<T>`, and `ArrayList<T>` support all twelve primitive element types. Non-null storage
uses the corresponding primitive array, including compact byte/short arrays. Nullable storage holds nominal
boxes or null. Growth, mutation, removal, iteration, and search preserve values through specialized helpers
and `List<T?>` / `List<Any?>` views without copying the list. Generic floating equality and collection search
equate NaNs and distinguish signed zeros, while direct primitive floating equality retains IEEE behavior. The
existing 256 class/function specialization limits still apply. Available in `stdlib:core` 1.9.0.

**Evidence:** `primitive lists preserve storage mutation nullable and Any views` inspects every backing array
and executes four programs through `testKotlinMutableListVmConformance`.

### Mutable lists

**Status:** Partial.

Public `MutableCollection<T>` and `MutableList<T>` expose `add(element)`, `remove(element)`, `clear`, indexed
`add`, `set` returning the previous element, and `removeAt`. `MutableIterable<T>.iterator()` returns a
`MutableIterator<T>` with `remove`. `ArrayList<T>()` and `ArrayList<T>(initialCapacity)` implement these
contracts with growing arrays and existing VM allocation quotas. All twelve statically typed primitive
families use unboxed array storage and mutation; nullable primitives use managed boxes. Supported reference
elements retain identity, and removed slots are cleared. Read-only `List` aliases observe the same mutations;
supported `List<Any>` and `List<Any?>` views use universal read bridges. `MutableList` remains invariant.
Iterator removal requires one preceding `next` and adjusts the iterator position. Structural changes
invalidate later `next`/`remove` calls, while `set` is non-structural. Invalid capacities, indexes, and
iterator states produce catchable `IllegalArgumentException` through library preconditions. Bulk operations,
construction from a collection, `listIterator`, and `subList` are not yet implemented.

**Evidence:** `MinimalScriptLoweringTest`, tests `mutable ArrayList preserves growth mutation and read only
views` and `mutable list element types remain invariant`; `testKotlinMutableListVmConformance` executes
mutation, failure, and heap quota scenarios with bounded slices.

### Iterable transformation

**Status:** Supported.

`Iterable<T>.map(transform: (T) -> R): List<R>` creates a new list, invoking the transform exactly once per
element in iteration order. Empty inputs return an empty list without invoking it. Element and result types
specialize independently across supported list element representations, including `Int`, nullable `Int`,
nullable references, and Guest classes. Identity transforms preserve references and existing Int boxes.
Generic callers can forward transforms. A `Collection<T>.map` overload reserves the receiver's initial `size`,
avoiding backing-array growth for ordinary lists and statically typed collections; an `Iterable<T>` receiver
keeps the general growing path. Both traverse once and preserve element order and transform side effects. The
implementation uses public `ArrayList<R>` and existing VM memory quotas; input lists remain unchanged unless
modified by the transform. Both overloads are inline and support non-local returns from direct lambdas.

**Evidence:** `MinimalScriptLoweringTest`, test `Iterable map preserves order independent types and nullable
identity`, executed by `testKotlinMapVmConformance`.

### Non-null transformation

**Status:** Supported.

`fun <T, R : Any> Iterable<T>.mapNotNull(transform: (T) -> R?): List<R>` invokes the transform once per
element in iteration order, including nullable inputs, and appends only non-null results to a new growing
`ArrayList<R>`. Empty inputs invoke no transforms; all-null results produce an empty list. Input and result
types specialize independently; nullable Int results are unboxed into ordinary Int storage, while supported
references and values returned as `Any` retain identity. Generic forwarding is supported. There is no
intermediate mapped list. Transform and selection occur together for each element, so this explicit operation
has different side-effect ordering from a separate `map` followed by `filter`. It is inline and supports
non-local returns from direct lambdas.

**Evidence:** `MinimalScriptLoweringTest`, test `Iterable mapNotNull preserves traversal narrowing and
identity`, executed by `testKotlinMapNotNullVmConformance`.

### Iterable filtering

**Status:** Supported.

`Iterable<T>.filter(predicate: (T) -> Boolean): List<T>` creates a new list of matching elements in iteration
order. Each element reaches the predicate once, including null values; empty inputs do not invoke it.
Duplicate matches remain duplicated. Filtering retains the original element type, including nullability;
`filter { it != null }` does not narrow `List<T?>` to `List<T>`. Stored reference and nullable Int box
identity is preserved. Generic callers can forward predicates, and the result uses public `ArrayList<T>` with
VM allocation quotas. The imported extension is inline and supports non-local returns from direct lambdas.

**Evidence:** `MinimalScriptLoweringTest`, test `Iterable filter preserves traversal nullable elements and
identity`, executed by `testKotlinFilterVmConformance`.

### Destination collection operations

**Status:** Supported.

`mapTo`, `filterTo` and `mapNotNullTo` append results to a caller-supplied `MutableCollection` in iteration
order and return the same destination with its concrete type preserved. Existing contents are retained; empty
or unmatched inputs leave the destination unchanged. Transforms and predicates run once per input element,
including nulls. `mapTo` preserves nullable results; `mapNotNullTo` excludes null results and narrows their
type. Supported generic wrappers and destinations with a wider element type retain reference and box identity
where appropriate. These extensions are inline and support non-local returns from direct lambdas. They
allocate no intermediate result list; destination growth, transforms and iteration can still allocate.
Programs explicitly call `clear()` before refilling a reusable buffer; clearing removes references while
retaining backing capacity. Use a separate destination when traversing a mutable source.

**Evidence:** `MinimalScriptLoweringTest`, test `Iterable destination operations append preserve types and
return identity`, executed by `testKotlinDestinationVmConformance`.

### Non-null element selection

**Status:** Supported.

`fun <T : Any> Iterable<T?>.filterNotNull(): List<T>` excludes nulls and narrows the element type while
preserving order and duplicate matches. `Int?` elements are checked and unboxed into the result's ordinary Int
storage; supported reference values retain identity. An `Any?` input produces `List<Any>` and retains stored
boxes and objects. Empty and all-null inputs produce empty lists. Generic wrappers, custom nullable iterables,
and non-null library list inputs are supported. Library lists and `ArrayList` can widen to read-only nullable
element views without copying; scalar Int reads through these views box, while reference reads preserve
identity. Mutable list element types remain invariant. This callback-free function remains non-inline.

**Evidence:** `MinimalScriptLoweringTest`, test `Iterable filterNotNull narrows boxed Int and reference
elements`, executed by `testKotlinFilterNotNullVmConformance`.

Callback inlining evidence: `inline collection literals avoid closures while stored callbacks retain
ownership` in `MinimalScriptLoweringTest`; non-local returns across selection, fold, transformation and
destination operations, enclosing generic class parameters and receiver evaluation execute in
`testKotlinMapVmConformance`. Native IDE diagnostics admit collection non-local returns in
`DiagnosticQueryTest`; transparent host waits through collection callbacks execute in
`testKotlinTransparentCallVmConformance`.

### Other standard collections and functional helpers

**Status:** Unsupported.

Sets, maps, sequences and collection conversion helpers have no Guest implementation.

**Related work:** not scheduled

Multi-field value classes use nominal managed boxes in list storage. Their construction, filtering, mapping
and mutable-list updates execute in `testKotlinMfvcVmConformance`; direct primitive list storage remains
unboxed.
