---
layout: default
title: Core helpers, text and value conversion
section: developers
permalink: /STDLIB-SUPPORT/core-text/
---

# Core helpers, text and value conversion

[← Guest standard library support]({{ '/STDLIB-SUPPORT/' | relative_url }})

* On this page
{:toc}

This is an executable API inventory for the current checkout. [Status and evidence policy]({{ '/STDLIB-SUPPORT/#status-and-evidence' | relative_url }}) applies to every entry. Signatures are indexed in
the [generated Guest API reference]({{ '/guest-api/' | relative_url }}).

## Scope and iteration helpers

### Scope functions

**Status:** Supported.

`let`, `run`, `with`, `apply`, `also`, `takeIf`, and `takeUnless` are available without imports for supported
Guest receiver types, including nullable references. Both receiver and receiver-free `run` are available. They
are inline: direct lambdas support non-local returns; `let` and `also` receive `it`, while `run`, `with`, and
`apply` use a receiver lambda. `apply` and `also` return the original receiver; `takeIf` and `takeUnless`
evaluate the predicate once and return the original receiver or null.

**Evidence:** `testKotlinScopeVmConformance` and `MinimalScriptLoweringTest`, test `stdlib scope functions
execute with inline receiver and nullable semantics`.

### Iteration helpers

**Status:** Supported.

`Iterable<T>.forEach` and `forEachIndexed` visit elements in order without creating a result collection;
`forEachIndexed` starts at index zero. Import `kotlin.collections.*` for these iterable helpers.
`repeat(times)` is available without an import, calls its action with indexes from zero to `times - 1`, and
does nothing for non-positive counts. Direct lambdas are inline and support non-local returns.

**Evidence:** `testKotlinScopeVmConformance` and `MinimalScriptLoweringTest`, test `stdlib scope functions
execute with inline receiver and nullable semantics`.

## Preconditions

### Preconditions and explicit failure

**Status:** Supported.

`require` throws `IllegalArgumentException`; `check` and `error(String): Nothing` throw
`IllegalStateException`. Both Boolean preconditions accept inline lazy `() -> Any` messages, matching the
stdlib signature. The body runs once on failure, then its result converts through virtual `toString`; both
body and conversion exceptions propagate unchanged. Successful conditions evaluate neither the body nor the
conversion. `Any` is non-null; use a nullable value's `toString()` explicitly for a nullable message.

**Evidence:** `assertions and exhaustive reference when share exceptions for vm execution`
(`testKotlinExceptionsVmConformance`) and `toString dispatch and lazy Any messages preserve values and effects
for vm execution` (`testKotlinToStringVmConformance`).

**Related work:** [#683](https://github.com/CertifiedBadIdeas/Compukters/issues/683)

## Strings and parsing

### `String` operations

**Status:** Partial.

Literals, concatenation, interpolation lowered as concatenation, `length`, indexed `get`, `substring`,
equality, direct `compareTo(String)`, the `<`, `<=`, `>`, and `>=` operators, and construction from
`CharArray` map to verified VM operations. `compareTo` orders UTF-16 code units and returns the first
differing code-unit difference, or the length difference for a prefix. The core library also provides
`startsWith(prefix: String)`, `endsWith(suffix: String)`, `contains(other: String)`, and `indexOf(other:
String, startIndex: Int = 0)` and `toIntOrNull()`. `toIntOrNull()` accepts optional `+` or `-` followed by
ASCII decimal digits and returns null for invalid input or values outside the `Int` range. Search uses UTF-16
code units; a negative start index begins at zero, and an empty search string returns the start index clamped
to the string length. Case-sensitive Char search and backward `lastIndexOf` searches are also available, along
with emptiness/blank checks, Unicode-whitespace `trim`, `trimStart`, `trimEnd`, Char/String `substringBefore`,
`substringAfter` and their `Last` variants, and `removePrefix`/`removeSuffix`. Extraction overloads accept an
explicit missing-delimiter fallback; otherwise they return the input. Reverse search defaults to the last
character index (including for an empty needle); explicit starts clamp to the last possible match.
Case-sensitive `split(delimiter: Char|String, limit: Int = 0)` returns `List<String>` and retains trailing
empty parts; zero means unlimited, positive limits retain the unprocessed suffix, and negative limits raise an
argument failure. Empty String delimiters split at UTF-16 boundaries. `lines()` handles CRLF, LF and CR and
retains the final empty line. `replace(Char, Char)` and `replace(String, String)` handle nonoverlapping
matches; an empty old String inserts at every UTF-16 boundary. Replacement checks output-length overflow
before allocation and materializes through one sized `CharArray`; both splitting and replacement respect
managed heap quotas. Regex, case-insensitive operations and multiple-delimiter overloads remain unavailable.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `shell language subset lowers control flow scalars strings and raw terminal calls` and `String compareTo
and ordering operators lower UTF-16 order for vm conformance`, with VM scenario
`testKotlinStringCompareVmConformance`; test `text stdlib preserves UTF16 cleanup search extraction and
transformations` with VM scenario `testKotlinTextStdlibVmConformance`; also
[`text_tests.rs`](https://github.com/CertifiedBadIdeas/Compukter-VM/blob/main/src/execution/text_tests.rs),
tests `string_content_operations_use_kotlin_utf16_semantics`,
`string_concat_selects_utf16_for_bmp_and_surrogate_code_units`, and
`string_substring_preserves_full_identity_and_freshens_proper_ranges`, plus `MinimalScriptLoweringTest`, test
`primitive char array lowers deterministically for exact utf16 materialization`, exercised by
`testKotlinSubsetVmConformance` for the text helpers and integer parsing.

**Related work:** #676

Only the documented parsing helper `String.toIntOrNull()` is available. Additional numeric parsers,
`kotlin.math`, regex, locale-sensitive conversion, Unicode category APIs and general formatting are outside
this inventory. Primitive operators and conversions are listed under [numeric semantics]({{ '/KOTLIN-SUPPORT/primitives/' | relative_url }}).

## Value text and hashing

### `toString()` for supported Guest values

**Status:** Supported.

Direct calls, string templates, and `String.plus` share the conversion path. All twelve primitive types use
their canonical scalar conversion, including unsigned decimal formatting; String returns itself, Unit becomes
`kotlin.Unit`, and nullable receivers produce `null` when absent. Scalar values passed through `Any` use
managed typed boxes, including checked `is`/`as` access. Virtual calls select user-defined or inherited
overrides, including separately compiled library methods. `super.toString()` calls the selected superclass
body directly. Without an override, an object or supported array produces `qualifiedRuntimeType@hexIdentity`;
identity is stable while live and contains no host address. Conversion and concatenation evaluate operands
once in source order and remain sliceable under allocation/GC quotas. Generated data-class/enum methods remain
subject to the [object-model boundary](../kotlin/objects.md).

**Evidence:** `toString dispatch and lazy Any messages preserve values and effects for vm execution`
(`testKotlinToStringVmConformance`, 64 KiB heap with repeated allocation), `source library generic functions
and classes specialize in consumer` (`testKotlinGenericLibraryVmConformance`), native
`reference_default_string_conversion_handles_null_unicode_and_tiny_slices` and
`reference_string_conversion_requires_abi_1_10_and_a_reference_operand`, and IDE test `toString and Any lazy
assertion messages share canonical IDE semantics`. New reference-default conversions and root-method
inheritance require Runtime ABI 1.10.

**Related work:** [#683](https://github.com/CertifiedBadIdeas/Compukters/issues/683)

### `hashCode()` for supported Guest values

**Status:** Supported.

Int and Char use their values, Long folds high/low bits, Boolean uses 1231/1237; Float hashes canonical NaN
bits and Double folds canonical 64-bit NaN bits, both distinguishing signed zero. String hashes UTF-16 content
with wrapping multiplication by 31. Nullable receivers hash to zero when absent; scalar boxes and String
retain value hashes through `Any`. User-defined and inherited overrides dispatch virtually, including compiled
libraries; `super.hashCode()` invokes the selected superclass directly. Receiver evaluation happens once and
exceptions from overrides remain catchable. Default objects and arrays hash their stable live VM identity,
including across GC. Freed slots may be reused; this identity is not persisted across VM recreation. Unit uses
singleton identity. Supported data-class primary-constructor properties combine value hashes, excluding body
properties. Supported array properties use element hashes with initial value one and null zero; ordinary
arrays use identity. This does not add Set/Map or complete generated data-class/enum support.

**Evidence:** `hashCode values and virtual dispatch preserve equality and effects for vm execution`
(`testKotlinHashCodeVmConformance`, 64 KiB heap and repeated allocation), `source library generic functions
and classes specialize in consumer` (`testKotlinGenericLibraryVmConformance`), artifact tests `value hash
requires ABI 1 11 matching source and I32 destination` and `value and string hashes encode canonical forms and
fixed costs`, native `value_hash` tests, and IDE test `toString and Any lazy assertion messages share
canonical IDE semantics` with hashCode diagnostics/completion. Typed value hashing requires Runtime ABI 1.11;
the nullable extension belongs to `stdlib:core` 1.7.0.

**Related work:** [#684](https://github.com/CertifiedBadIdeas/Compukters/issues/684)
