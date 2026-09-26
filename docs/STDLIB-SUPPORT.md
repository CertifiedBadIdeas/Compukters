---
layout: default
title: Guest standard library support
description: The Kotlin standard-library APIs available to Compukters programs.
permalink: /STDLIB-SUPPORT/
---

# Guest standard library support

Compukters provides a small, versioned Guest Kotlin library. It does not load the Kotlin/JVM standard library into a
computer. This page describes callable library APIs; [Guest Kotlin support]({{ '/KOTLIN-SUPPORT/' | relative_url }})
describes language syntax and lowering. The matrix describes this repository revision, including unreleased work.

## Status legend

- [x] **Supported** — the stated API has execution evidence in the Rust VM.
- [ ] **Partial** — the named subset works, with the stated limitations.
- [ ] **Unsupported** — declarations may exist for K2, but programs cannot execute that API.

The canonical declarations live in [`guest-platform`](https://github.com/CertifiedBadIdeas/Compukters/tree/dev/modules/common/guest-platform/src/platform).
Only selected Compukters platform modules participate in Guest name resolution; host Kotlin/JVM dependencies do not.
The [Guest API reference]({{ '/guest-api/' | relative_url }}) indexes public declarations by package and symbol and
links to their source files.

## Core and text

- [x] **`require(Boolean)`** — true returns `Unit`; false raises a Guest argument failure. Evidence:
  [`Assertions.kt`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/guest-platform/src/platform/libraries/stdlib-core/kotlin/Assertions.kt)
  and the assertions executed by `testKotlinReferenceArrayVmConformance`.
- [ ] **`String` basics — Partial** — `length`, indexed UTF-16 access, concatenation, equality, and `substring` execute.
  `startsWith`, `endsWith`, `contains`, and `indexOf` are provided by `stdlib:core`; `indexOf` accepts an optional start
  index. Regex, locale-sensitive case conversion, Unicode categories, and broad formatting are absent. Evidence:
  [`TextSearch.kt`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/guest-platform/src/platform/libraries/stdlib-core/kotlin/text/TextSearch.kt),
  `testKotlinSubsetVmConformance`, and
  [`text_tests.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/execution/text_tests.rs).
  Tracking: not scheduled
- [ ] **Numbers and characters — Partial** — the supported `Int`, `Long`, `Float`, `Boolean`, and `Char` operations use
  Guest scalar values. `Byte`, `Short`, `Double`, parsing helpers, and the Kotlin math package are unavailable. Evidence:
  `testKotlinLongVmConformance` and `testKotlinFloatVmConformance`. Tracking: not scheduled

## Arrays and ranges

- [ ] **`Array<T>` — Partial** — `emptyArray<T>()` and direct `arrayOf(...)` work for `String`, supported
  Guest class references, and `Any`, including nullable forms and boxed `Int?`, including specializations inside generic Guest functions. `size`, indexed `get`,
  and indexed `set` use the array's concrete element type. `Array<Any>` boxes `Int` on construction or indexed writes,
  preserves reference identity, and returns the stored box on repeated reads. Factory arguments are evaluated once in
  source order; reading an uninitialized non-null reference traps. `copyOfRange` is available only for `Array<String>`.
  `Array<Int>`, other primitive-to-`Any` boxing, spread arguments, and general array iterators are
  unavailable. Evidence:
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  tests `string arrays can be constructed read and written`, `reference arrays preserve Guest class elements and aliases`,
  and `reference arrays reject unsupported element representations`; VM tasks `testKotlinReferenceArrayVmConformance`
  and `testKotlinArgvVmConformance`. Tracking: [#656](https://github.com/CertifiedBadIdeas/Compukters/issues/656)
- [x] **`IntArray`** — constructor by size, `intArrayOf`, `size`, indexed get/set, and direct `for` iteration use dense
  unboxed `Int` storage. Evidence: `testKotlinIntArrayVmConformance` and
  [`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
  test `specialized IntArray lowers deterministically for vm conformance`.
- [x] **`CharArray`** — construction by size, indexed get/set, `size`, and UTF-16 string materialization execute.
  Evidence: `testKotlinSubsetVmConformance` and
  [`kotlin_writer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs),
  test `k2_char_array_program_executes_exact_utf16_materialization`.
- [ ] **Other primitive arrays — Unsupported** — `BooleanArray`, `ByteArray`, `ShortArray`, `LongArray`, `FloatArray`,
  `DoubleArray`, and unsigned arrays have no executable Guest API. Tracking: not scheduled
- [ ] **`IntRange` and `IntProgression` — Partial** — direct `for` loops over `..`, `until`, `..<`, `downTo`, and one
  positive `step` compile to unboxed scalar loops. Stored range objects and general iteration do not execute. Evidence:
  `testKotlinIntLoopsVmConformance` and
  [`IntRange.kt`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/guest-platform/src/platform/libraries/stdlib-ranges/kotlin/ranges/IntRange.kt).
  Tracking: not scheduled

## Collections and I/O

- [ ] **Read-only `List<T>` — Partial** — `listOf` and `emptyList` create typed lists of `Int`, `String`, or
  supported Guest class references. `size`, indexed `get`, and direct `for` iteration work; an out-of-range index
  traps. `Int` storage and typed reads remain unboxed. A `List<Int>` can widen to `List<Any>` without changing list
  identity; universal reads box each `Int`, and `is Int` / `as Int` recover its type and value. Supported reference lists
  also widen to `List<Any>` without copying their elements. Direct `listOf<Any>` construction stores mixed boxed `Int`
  and supported references in one array; indexed reads and iteration preserve element identity. `contains` / `in` and
  `indexOf` search with supported value equality and return the first index or `-1` when absent. Non-null `Any` supports
  boxed `Int` value equality, string content equality, explicit Guest `equals` overrides, and data-class equality for
  supported constructor properties. Other classes use default identity equality. Hashing and text dispatch remain
  unavailable. Nullable elements, including `Int?`, preserve null in storage, iteration, and searches. Nullable and non-null
  lists widen to `List<Any?>` without copying. Spread arguments are unsupported. Evidence:
  `testKotlinNullableCollectionsVmConformance`, `testKotlinListVmConformance`, `testKotlinListAnyVmConformance`, `testKotlinListAnyQuotaVmConformance`,
  `testKotlinListBoundsVmConformance`, `testKotlinListQuotaVmConformance`, and
  [`Lists.kt`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/guest-platform/src/platform/libraries/stdlib-collections/kotlin/collections/Lists.kt). Tracking:
  [#656](https://github.com/CertifiedBadIdeas/Compukters/issues/656),
  [#581](https://github.com/CertifiedBadIdeas/Compukters/issues/581)
- [x] **Nullable element selection** — `Iterable<T>.firstOrNull` / `lastOrNull` support predicate and no-predicate
  forms, including user-defined iterables. List overloads read first/last elements by index; predicate selection for
  `List.lastOrNull` searches from the end, whereas `Iterable.lastOrNull` traverses forward. `List.getOrNull` returns
  null for invalid indexes. Empty or unmatched selections return null. `Int` elements produce `Int?`; nullable
  references and existing Int boxes retain identity. Evidence: `testKotlinCollectionSelectionVmConformance` and
  `MinimalScriptLoweringTest`, test `collection nullable selection preserves traversal values and identity`.
- [x] **Iterable accumulation** — `Iterable<T>.fold(initial: R, operation: (R, T) -> R)` visits elements in order,
  calling the operation once per element. Empty iterables return the initial value unchanged. Element and accumulator
  types are independent, including nullable references, `Int?`, and supported Guest classes. Evidence:
  `testKotlinFoldVmConformance` and `MinimalScriptLoweringTest`, test
  `Iterable fold specializes independent element and accumulator types`.
- [ ] **Other collections and functional helpers — Unsupported** — `MutableList`, `Set`, `Map`, sequences, `map`,
  `filter`, and general custom iterator loops have no Guest implementation. Tracking: not scheduled
- [ ] **Console I/O — Partial** — `print`, `println`, and `readln` support the documented scalar and string forms through
  the terminal capability. Formatting and other overloads are absent. Evidence: `testKotlinSubsetVmConformance` and
  [`computer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/host/compukter-vm/src/computer.rs),
  test `stdio_read_line_echoes_then_writes_stdout_and_stderr_in_order`. Tracking: not scheduled
- [ ] **Exceptions, reflection, coroutines — Unsupported** — general Kotlin exception handling, reflection, and
  coroutine libraries are not available to Guest programs. Tracking: not scheduled

## Maintenance

When an API becomes executable, update its exact surface and evidence here. Update
[Guest Kotlin support]({{ '/KOTLIN-SUPPORT/' | relative_url }}) when the source-language boundary changes too.
