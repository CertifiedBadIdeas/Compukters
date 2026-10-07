---
layout: default
title: Arrays, ranges and progressions
section: developers
permalink: /STDLIB-SUPPORT/arrays-ranges/
---

# Arrays, ranges and progressions

[← Guest standard library support]({{ '/STDLIB-SUPPORT/' | relative_url }})

* On this page
{:toc}

This is an executable API inventory for the current checkout. [Status and evidence policy]({{ '/STDLIB-SUPPORT/#status-and-evidence' | relative_url }}) applies to every entry. Signatures are indexed in
the [generated Guest API reference]({{ '/guest-api/' | relative_url }}).

## Arrays

### UTF-16 `CharArray` materialization

**Status:** Supported.

`CharArray(size)`, indexed access, mutation, `size`, `concatToString(start, end)`, and `String(array, start,
length)` preserve exact UTF-16 code units through Guest execution.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
test `primitive char array lowers deterministically for exact utf16 materialization`, and
[`kotlin_writer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs),
test `k2_char_array_program_executes_exact_utf16_materialization`.

### Bulk array copying

**Status:** Supported.

All twelve primitive array families and admitted reference `Array<T>` support public `copyOf()`,
`copyOf(newSize)`, and `copyInto(destination, destinationOffset = 0, startIndex = 0, endIndex = size)`. Copies
preserve reference identity, overlap, empty ranges, truncation, and zero/null padding. Resized reference
copies return `Array<T?>`; `copyInto` admits assignable reference elements through an `Array<out T>` source.
Receivers and explicit arguments evaluate once. Native copying is bounded by the Guest budget without
temporary buffers; `ArrayList` growth uses these APIs. Negative sizes and invalid ranges throw typed catchable
exceptions through the shared [operation-error mechanism](../kotlin/nullability.md).

**Evidence:** `testKotlinIntArrayVmConformance`,
`bulk_array_copy_preserves_overlap_in_both_directions_across_tiny_budgets`, and
`bulk_array_instruction_resumes_with_exact_dynamic_cost_and_one_retirement`.

**Related work:** [#679](https://github.com/CertifiedBadIdeas/Compukters/issues/679)

### Reference `Array<T>` operations

**Status:** Partial.

Entry `Array<String>`, `emptyArray<T>()`, direct `arrayOf` calls, `size`, and indexed get/set work for
`String`, supported Guest class references, value classes, and `Any`, including nullable forms and nominal
primitive boxes, with concrete uses inside specialized generic functions. `Array<Any>` boxes supported scalars
when constructed or written, preserves object identity, and returns the stored reference on reads.
`arrayOfNulls<T>(size)` creates null-filled arrays for supported reference elements and nullable primitive
boxes, including generic specializations; negative sizes throw catchable `NegativeArraySizeException`.

**Evidence:** `testKotlinMutableListVmConformance`. `copyOfRange` is available only for `Array<String>`.
Primitive `Array<Int>`/`Array<Long>` and other primitive element arrays, spread arguments, iterators, and
higher-order operations are unavailable.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `string arrays can be constructed read and written` and `string arrays support copyOfRange and supported
default arguments`, plus `nullable Int and collection elements preserve values and nulls`
(`testKotlinNullableCollectionsVmConformance`), `reference arrays preserve Guest class elements and aliases`
and `reference arrays reject unsupported element representations`, paired with
`testKotlinReferenceArrayVmConformance` and
[`gc_tests.rs`](https://github.com/CertifiedBadIdeas/Compukter-VM/blob/main/src/execution/gc_tests.rs), test
`collector_scans_reference_arrays`.

**Related work:** [#656](https://github.com/CertifiedBadIdeas/Compukters/issues/656) Imported non-generic
nominal classes also work as reference-array elements, including nullable storage backing typed device lists.

**Evidence:** `text display program lowers deterministically for GameTest`, executed by the real text-display
scenario in `:v26_1-neoforge:runGameTestServer` with `TextDisplay.all()` and typed filtering.

### All twelve primitive array families

**Status:** Supported.

`BooleanArray`, `ByteArray`, `ShortArray`, `CharArray`, `IntArray`, `LongArray`, `FloatArray`, `DoubleArray`,
`UByteArray`, `UShortArray`, `UIntArray`, and `ULongArray` support size constructors, `(Int) -> T`
initializers, their `*ArrayOf` factories, `size`, indexed reads and writes, direct `for` loops, `copyOf`, and
overlapping `copyInto`, including its receiver-size default. Initializers receive ascending indexes exactly
once, and are never invoked for a negative size. New slots use the primitive zero value. Byte and short
families use compact one- and two-byte storage; unsigned families preserve their nominal identity and full
magnitude. Bounds and negative sizes raise managed exceptions. Stored iterators, `indices`, and spread factory
arguments remain outside the admitted subset.

**Evidence:** `all primitive operators preserve narrow signed unsigned and nominal semantics` in
`GuestInlineIntegrationTest`, executed by `testKotlinPrimitivesVmConformance`; `specialized IntArray lowers
deterministically for vm conformance` exercises copies and exceptions for every family via
`testKotlinIntArrayVmConformance`; platform bundle test `receiver array size defaults round trip and reject
incompatible declarations`.

### `DoubleArray`

**Status:** Supported.

Unboxed F64 storage supports `DoubleArray(size)`, `doubleArrayOf`, zero initialization, `size`, indexed
reads/writes, direct `for` loops with break/continue, `copyOf` (including resize with zero padding) and
overlapping `copyInto`. Factory arguments and loop sources evaluate once. Bounds and negative sizes raise
managed exceptions. Arrays retain identity equality; generated data-class hashing uses element hashes. No
boxed Double allocation is needed for element storage or direct iteration.

**Evidence:** `DoubleArray storage copying iteration and failures lower for vm conformance` and
`testKotlinDoubleArrayVmConformance`, plus the Guest Double IDE diagnostic test.

**Related work:** [#688](https://github.com/CertifiedBadIdeas/Compukters/issues/688)

Additional focused IntArray evidence: `specialized IntArray lowers to unboxed primitive array instructions`
and `unsupported IntArray forms publish no artifact` in `MinimalScriptLoweringTest` cover instruction
selection and rejection of the excluded array shapes. MFVC reference-array storage executes in
`testKotlinMfvcVmConformance`.

## Ranges and progressions

### Primitive ranges and progressions

**Status:** Supported.

Signed integral range operators widen to `IntRange` or `LongRange`; unsigned operators widen to `UIntRange` or
`ULongRange`. `CharRange` retains UTF-16 values. `..`, `..<`, `until`, `downTo`, positive `step`, membership,
stored progressions, chained steps, and iterators execute as ordinary Guest library code. `ClosedRange` and
`OpenEndRange` views retain their bounds and membership; requesting an exclusive upper bound for a range
ending at the primitive maximum raises `IllegalStateException`. Aligned progression endpoints avoid overflow
across the entire signed/unsigned range, and iterators stop before incrementing past the final element. Empty
progressions stay empty; an exhausted iterator throws `NoSuchElementException`. `Float`/`Double` closed and
open-end ranges support IEEE membership and empty checks, including NaN and signed zero; `Boolean` closed and
open-end ranges use its comparable ordering. These ranges do not define iteration. Direct `Int` loops retain
the [allocation-free language lowering](../kotlin/control-flow.md).

**Evidence:** `primitive ranges preserve bounds steps termination and floating membership`, `allocation free
Int loops lower deterministically for vm execution`, and `testKotlinIntLoopsVmConformance`.
