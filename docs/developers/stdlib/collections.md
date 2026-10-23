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

## Immutable enum entries

**Status:** Supported for admitted stateless Guest enum classes.

`EnumType.entries` returns `kotlin.enums.EnumEntries<E>`, an invariant type implementing read-only
`List<E>`. Elements retain declaration order and enum singleton identity. Every read for the same type
returns the same collection, including reads across a separately compiled platform or addon library.
Empty enums have a shared empty collection per type. The private backing array never escapes;
casting the collection to `MutableList<E>` fails rather than enabling mutation.

`size`, `isEmpty`, indexed access, `contains` / `in`, `indexOf`, `lastIndexOf` and ordinary iteration
use the existing managed collection interfaces, including reference-preserving `List<Any>`,
`List<Any?>` and nullable-element read views. Indexed access outside `0 until size` throws catchable
`IndexOutOfBoundsException`; exhausted iterators throw `NoSuchElementException`. Searches compare
singleton references and run in linear time. Each iterator is a fresh managed object. The cached collection
and backing array allocate during enum cache initialization; repeated `entries` reads do not allocate.
The collection follows the List equality, hashing and text rules below; other generated enum methods retain
their existing support limits.

**Evidence:** `MinimalScriptLoweringTest`, test `enum entries preserve ordered singleton references and
collection identity`, executed by `testKotlinEnumEntriesVmConformance` for project, platform and addon types.
`PlatformMetadataCompilerTest` covers generated getter identities; `CompletionQueryTest` and
`DiagnosticQueryTest` cover IDE source and metadata inputs.

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

### Numeric sums

**Status:** Supported for `Iterable` in `stdlib:core` 1.27.0.

`sum()` accepts `Byte`, `Short`, `Int`, `Long`, `Float`, `Double`, `UByte`, `UShort`, `UInt` and
`ULong` elements. Narrow signed values accumulate into `Int`; narrow unsigned values into `UInt`.
Other element types retain their numeric result type. Empty iterables return zero (positive zero
for floating results). Integer overflow wraps; floating addition follows traversal order with normal
rounding, infinity and NaN semantics.

`sumOf(selector)` supports `Int`, `Long`, `Double`, `UInt` and `ULong` selector results. K2 selects the
overload from the selector return type, including record properties and forwarded typed selectors.
Selectors run exactly once per visited element, in iteration order, without an intermediate list.
Empty inputs invoke no selector. Iterator/selector exceptions propagate, and direct inline lambdas
support non-local returns. The receiver is evaluated once. Arrays and sequences do not acquire
these iterable extensions automatically; this entry describes `Iterable` APIs.

**Evidence:** `MinimalScriptLoweringTest`, test `numeric iterable sums preserve types order overflow
and selector exits`, executed by `testKotlinSumsVmConformance` with the pinned Rust VM and bounded slices.

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
imports. Spread arguments and collection APIs not listed here remain unavailable. The lazy sequence subset is described below. Hash maps and
sets have the separate supported subset below.
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

## Hash maps and sets

**Status:** Partial.

`HashMap<K, V>(initialCapacity = 16)` implements `Map<K, V>` and `MutableMap<K, V>` with `size`, `isEmpty`,
`containsKey`, `containsValue`, indexed lookup, `put`, indexed assignment, `remove` and `clear`. `get`, `put`
and `remove` return nullable values; use `containsKey` to distinguish an absent key from a stored null.
`key in map` tests key membership; `isNotEmpty()` and `for ((key, value) in map)` are also supported.
`keys`, `values` and `entries` are live read-only views, including views
obtained before a map mutation. `Map.Entry` exposes `key` and `value`; a hash-map entry can be cast to the
matching `MutableMap.MutableEntry<K, V>` to use `setValue`, which returns the previous value.

`HashSet<T>(initialCapacity = 16)` implements `Set<T>` and `MutableSet<T>` with `size`, `isEmpty`, membership,
`add`, `remove`, `clear` and a removable iterator. Adding an equal element returns false; removing an absent
element returns false. `emptyMap<K, V>()` and `emptySet<T>()` create fresh empty collections with read-only
views. Read-only views are aliases rather than immutable snapshots. Map views do not expose collection
mutation operations.

`mapOf<K, V>(vararg pairs: Pair<K, V>)`, `mutableMapOf`, `setOf<T>(vararg elements: T)` and `mutableSetOf`
accept direct arguments, including empty forms with explicit types. They create fresh hash collections;
read-only results remain aliases of mutable objects. Every argument runs once in source order before any
hashing/equality callbacks from population. The last equal map key wins; equal set elements coalesce.
Spread arguments remain unsupported and produce a target diagnostic.

`Pair<A, B>(first, second)` and infix `first to second` preserve concrete component types, including nullable
primitives, Guest references and nominal value classes. Pairs support `first`, `second`, destructuring,
`copy(first, second)` with both arguments explicit, component-wise equality/hash and text `(first, second)`. Pair type parameters are invariant in the
current Guest generic-class subset; implicit component-type widening remains unsupported. `PairComponents` is its sealed read-only
component view; reads through this universal view use the existing scalar/value-class boxes.

Inline `MutableMap<K, V>.getOrPut(key, defaultValue)` returns an existing non-null value without invoking the
callback. For absent keys or stored nulls it invokes the callback once and stores its result, including null.
A null default is recomputed on the next call. Exceptions and non-local returns skip the implicit insertion;
explicit mutations inside the callback retain their normal effects. If the callback inserts the same key,
its returned default replaces that value. The operation is not an atomic synchronization primitive.
These APIs are available in `stdlib:core` 1.11.0 without a VM ABI change.

All twelve primitive families and supported reference types can be keys, values and elements. Null keys
and elements hash to zero. Equality and hashing use the existing generic Kotlin semantics: floating NaNs
coalesce, while positive and negative zero remain distinct. User `equals` and `hashCode` overrides retain
their effects and exceptions. Equal keys replace values; colliding unequal keys remain separate. Growth
reuses cached hashes. Keys must retain stable equality and hash codes while stored.

Value-class keys retain nominal value equality and hashing, including multi-field layouts. Equal instances
coalesce, while an `Int` and an Int-backed value class remain different keys through `Any?`. Nullable and
universal boundaries use the established nominal managed boxes. Value-class fields retain their existing
Guest representation; hash collections introduce no new boxing or unboxed-storage contract.

The table, entries, views and iterators are ordinary Guest objects charged to VM heap and instruction
budgets. Collision traversal, growth and iteration resume across quota slices. Removal and clear release
the table's references to removed entries. The existing 256 generic class/function specialization limits
remain in force. Iteration uses unspecified bucket order, deterministic for the same execution. Structural
insertion, removal or non-empty clear invalidates an existing iterator; replacing a value does not. Iterator
removal requires one preceding `next` and preserves that iterator's traversal. Invalid iterator state throws
`IllegalStateException`; exhaustion throws `NoSuchElementException`. Negative capacities or capacities above
1,073,741,824 throw `IllegalArgumentException`; actual allocation remains subject to the much smaller VM heap.

The current subset does not implement spread factories, bulk operations, mutable key/value/entry views, linked/sorted collections,
or collection type-argument widening. Retain exact key/value/element types in read-only views; for example,
`HashSet<Int>` to `Set<Int>` works, while `Set<Int>` to `Set<Any>` and `Map<String, Int>` to `Map<String, Any>`
produce a target diagnostic. Mutable contracts remain invariant.

**Evidence:** `MinimalScriptLoweringTest`, tests `hash maps and sets preserve collisions nulls mutation and
live views`, `hash collections support all twelve primitive families`, `hash collections preserve primitive
and nullable storage`, `hash collections preserve nominal value class keys`, and `hash collections retain
ordinary heap quota failures`, executed by `testKotlinHashCollectionsVmConformance`. Rejection coverage is
`mutable hash collections remain invariant` and `hash collection unsupported widening reports a target
diagnostic`. IDE analysis is covered by `DiagnosticQueryTest`, test `hash collection analysis admits typed
and value class keys`, with and without attached platform sources.

Factory/default evidence: `hash collection factories preserve arguments pairs and defaults` and
`hash collections support all twelve primitive families`, executed by `testKotlinHashCollectionsVmConformance`.
The IDE diagnostic test above also covers factories, Pair and getOrPut with and without attached sources.
The checked-in [inventory report example](https://github.com/CertifiedBadIdeas/Compukters/tree/dev/examples/inventory-report)
combines batch aggregation, uniqueness tracking and stock thresholds. `hash collection inventory example
compiles and executes` compiles its actual source; the same VM task checks its report across bounded slices.

**Related work:** [#705](https://github.com/CertifiedBadIdeas/Compukters/issues/705),
[#707](https://github.com/CertifiedBadIdeas/Compukters/issues/707).

### Other standard collections and functional helpers

**Status:** Unsupported.

Collection conversion helpers not listed on this page remain unavailable. The supported lazy
sequence subset is described below.

**Related work:** not scheduled

Multi-field value classes use nominal managed boxes in list storage. Their construction, filtering, mapping
and mutable-list updates execute in `testKotlinMfvcVmConformance`; direct primitive list storage remains
unboxed.

## Collection equality, hashing and text

**Status:** Supported in `stdlib:core` 1.26.0.

`ArrayList` (including `listOf` results) and `EnumEntries` compare lists by size and corresponding
ordered elements. Their hash starts at 1 and accumulates `31 * hash + element.hashCode()`.
`HashSet` compares sets independently of iteration order and sums element hashes. `HashMap`
compares key/value associations independently of iteration order, distinguishing a missing key
from a stored null; its hash sums entry hashes. Entries compare their key and current value and
hash as `key.hashCode() xor value.hashCode()`. All arithmetic uses ordinary Int overflow and
null contributes zero. Lists and sets never compare equal to one another.

Equal contents can compare across concrete type specializations and through `Any`, including
nullable primitives and nominal value classes. Generic floating comparisons make NaNs equal and
keep signed zeros distinct. Scalar and value-class reads use the established managed boxes.
Supported custom reference implementations of List, Set, Map, Entry and Iterator receive hidden
read adapters from the compiler; comparison does not require inheriting a particular implementation.
Built-in hash adapters reuse the hash table; custom Set/Map adapters scan typed elements/entries,
so comparing custom containers can take quadratic time.
Custom implementations still choose their own `equals` and `hashCode` methods; the interfaces do
not supply structural equality automatically. The adapters expose no writes and add no public API.
Mutable contracts stay invariant, and previously unsupported public hash-collection widening remains
unsupported. Reading equal values across specializations does not make unlike nominal values equal.

Map `keys` and `entries` views have Set equality/hash; `values` retains Collection identity equality
and hashing, including between two separately obtained values views. All views reflect later map
changes. Do not mutate collections used as map keys or set elements: content-based hashing changes
when their contents change, just as mutable element hashes can change.

Collection text uses `[a, b]`, map text `{key=value}`, and entry text `key=value`; null appears as
`null`. Empty collections print `[]`, empty maps `{}`. Map and set order remains unspecified.
A direct self element prints `(this Collection)`; a map directly used as its own key or value prints
`(this Map)`. Arbitrary indirect cycles in equality, hashing or text are not detected.
Element equality, hash and text callbacks retain effects and propagate exceptions. Text uses a
managed StringBuilder; traversal, boxing and text allocation consume normal heap/instruction budgets.
Nested generic collection types remain subject to the compiler’s specialization limits.

**Evidence:** `MinimalScriptLoweringTest`, tests `collection value semantics compare specializations
entries and live views` and `collection value semantics preserve all scalar families and floating boxes`,
executed by `testKotlinCollectionSemanticsVmConformance`. The fixtures cover nested collections,
custom reference implementations and iterators, project/platform/addon enum entries, live views, all twelve scalar families,
nullable/mixed types, value-class identity, self text and callback exceptions. Existing mutation,
iterator, quota and variance scenarios remain in `testKotlinHashCollectionsVmConformance`.

## Lazy sequences

**Status:** Partial; the following subset is executable in `stdlib:core` 1.25.0.

`kotlin.sequences.Sequence<out T>` is a separate iterator-producing interface. Import
`kotlin.collections.asSequence` for `Iterable<T>.asSequence()` and the `kotlin.sequences`
extensions `map`, `mapNotNull`, `filter`, `filterNot`, `filterNotNull`, `take`, `firstOrNull` (with or without predicate),
and `toList`, plus terminal `forEach`, `fold`, `count` (with or without predicate),
`any` and `none` (with or without predicate), and `all(predicate)`. Lists, sets and custom
iterables can supply the source. A sequence borrows
its source; it does not copy or freeze it. Source mutation and iterator invalidation follow
the source's own contract. Repeating traversal creates new wrapper iterator state and runs
callbacks again, provided the source itself permits repeated traversal.

Constructing a chain invokes no source iterator and no callbacks. `map` invokes its retained
callback on `next`; filtering searches on demand from `hasNext` or `next`, caching one
accepted element. Repeated `hasNext` reuses that cache. Null is an ordinary element and
is distinct from exhaustion. `take(count)` validates non-negative counts immediately;
`take(0)` does not request even a source iterator. Taking a prefix or finding the first
match stops consuming at that point. `toList` consumes all available values in order and
allocates a new list. An unbounded source may exhaust ordinary execution or heap quotas.

`mapNotNull` invokes its retained transform once per consumed source element and drops null
results. It composes mapping with the shared non-null selection iterator; repeated `hasNext`
reuses a cached accepted result. `filterNotNull` drops null source elements and exposes
`Sequence<T>` from `Sequence<T?>`, with `T : Any`; `mapNotNull` likewise returns a non-null
result element type. Both preserve order, create independent iterator state and stop when
the downstream consumer stops. Empty/all-null sources exhaust normally. Primitive nullable
results cross the ordinary boxing boundary, then accepted values use the non-null scalar
cache. Reference caches clear their consumed slots. Wrappers/iterators are managed objects;
no intermediate result list is created.

Intermediate callbacks escape and are not inline, so they cannot use non-local returns.
The immediate predicate overload of `firstOrNull` is inline and admits non-local returns.
Terminal operations request a fresh iterator per invocation. `forEach` and `fold` visit in order;
`fold` evaluates its initial accumulator once and returns it unchanged for empty inputs.
`count` consumes the entire sequence and counts null elements normally; its predicate overload
counts only matches. A count exceeding `Int.MAX_VALUE` throws `ArithmeticException`.
`any(predicate)` and `none(predicate)` stop at the first match; `all(predicate)` stops at
its first failure. Empty inputs return false for `any`, true for `all`/`none`, and zero for
`count`, without invoking callbacks. Parameterless `any`/`none` check `hasNext` without
calling `next`; an upstream filter may still consume source elements to establish availability.
All immediate terminal callbacks are inline and admit non-local returns. No terminal operation
constructs a result list; upstream wrappers and iterators retain their ordinary allocation costs.

Source and callback exceptions propagate. Exhausted filtering, empty and count-limited
iterators throw `NoSuchElementException`; mapping propagates its source iterator's
exhaustion behavior. Each wrapper and iterator is a managed object; callbacks retain
captures. Filtering uses a specialized one-value cache: all twelve non-null primitive families retain
unboxed scalar fields, while nullable and reference values keep their references. Consuming
a reference clears its cached slot. The cache is one additional managed object per filtering
iterator; typed mapping and result lists use existing specialization. Nullable terminal results
and universal views keep their ordinary boxing boundaries. Avoiding intermediate lists does not guarantee
lower instruction cost or faster execution for small inputs.

`sequence { yield(...) }`, `generateSequence`, `sequenceOf`, array/iterator adapters and
other sequence operations are outside this supported subset. Covariant primitive-to-nullable and
primitive-to-Any/Any? Sequence and Iterator views preserve the source object and specialize reads
through the compiler's [covariant interface bridges](../kotlin/functions.md#covariant-interface-read-views).
Nullable selection can consume a widened source without an explicit nullable map result type.
Ordinary generic helpers can forward existing
callbacks or construct retained lambdas and bound member references for `filter`, `filterNot` and
`map`/`mapNotNull`. Their owners and generic callers specialize before closure layout, so captures retain their
concrete scalar/reference types. Helpers returning a predicate or a sequence retain captured values
and shared mutable cells after the helper returns. Direct terminal calls inside generic helpers are
covered separately for primitive, nullable and reference elements.

**Evidence:** `MinimalScriptLoweringTest`, test `sequences preserve lazy demand iterator state
and nullable values`, executed by `testKotlinSequencesVmConformance` with 256-unit slices.
The fixture covers deferred callbacks and order, early stopping, independent interleaved
iterators, repeated availability checks, empty/exhausted inputs, count validation, borrowed
mutable sources, retained captures, callback exceptions, references, nullable values and all twelve primitive families, including integer boundaries, NaNs and signed zero.
The same lowering test inspects scalar cache layouts, checks that cache methods and primitive
filtering steps allocate no boxed values, and verifies reference-slot clearing.
The same main/signed/unsigned fixtures execute terminal operations across all twelve primitive
families, nullable elements and reference accumulators, including ordered non-commutative folds,
empty inputs, availability-only queries, early stopping, non-local returns, callback/upstream
exceptions and single evaluation of the initial accumulator. `checkGenericTerminals` exercises
immediate terminal callbacks through an ordinary generic helper with Int, Int? and reference elements.
`checkGenericSequence` exercises retained filter/filterNot/map callbacks, generic forwarding callers,
escaping predicate factories and bound member references across all twelve primitive families,
nullable elements and references. The main fixture also covers different source/result types and
mutation of captured generic values before returning a sequence. `generic sequence helpers specialize
retained callback signatures` protects both forwarded and locally constructed callback lowering.
Overflow guards are not exercised at the multi-billion-element threshold by this bounded conformance scenario.
`checkNullSelection` runs filterNotNull and mapNotNull through generic helpers across all twelve
primitive families and reference values. `testNullSelection` exercises exact callback demand,
repeated availability checks, nullable inputs, non-null result types, early stopping, independent
iterators, empty/all-null inputs, exhaustion, reference identity, mutable captures and callback/upstream
exceptions. Scalar cache and no-boxing assertions also cover NotNullIterator.calculateNext.
`sequences reject non local returns from deferred callbacks` covers map/mapNotNull/filter/filterNot rejection.
`checkCovariantViews` additionally exercises all twelve primitive families through nullable and
Any/Any? Sequence and Iterator views, checking identity, shared traversal state and exact callback
demand. `checkCustomCovariance` covers custom/inherited producers, nested results, nulls and failures.
`DiagnosticQueryTest`,
`lazy sequences resolve with source and metadata inputs`, covers both IDE platform modes.
