---
layout: default
title: Expressions and control flow
section: developers
permalink: /KOTLIN-SUPPORT/control-flow/
---

# Expressions and control flow

[← Guest Kotlin support]({{ '/KOTLIN-SUPPORT/' | relative_url }})

* On this page
{:toc}

This page describes the current checkout, including unreleased work. [Status and evidence policy]({{ '/KOTLIN-SUPPORT/#status-and-evidence' | relative_url }}) applies to every entry.

## Scalar `when` with individual branches

**Status:** Supported.

`Int`, `Char`, `Boolean`, and `String` subjects, plus subjectless boolean conditions, lower to bounded
deterministic branches; matched and fallback paths execute in the VM.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `bounded when lowers deterministically for vm execution` and `bounded when forms compile for admitted
scalar types`, and
[`kotlin_writer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs),
test `k2_bounded_when_selects_matched_and_fallback_branches`.

## Pattern-rich `when`

**Status:** Partial.

The tested constant scalar range-membership and comma-joined branches accept Unit statements. Broader pattern
combinations are outside this support claim. Type branches over the admitted sealed class subset are handled
separately under the object model.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
test `when range and alternative branches accept Unit statements`.

**Related work:** not scheduled

## `if`, blocks, mutable locals, and `while`

**Status:** Partial.

These forms compile and are used by the checked-in shell, including nested loops and reassignment. `break` and
`continue` targeting the current innermost `while` lower directly; jumps to an outer loop are rejected. There
is no source-level conformance suite covering every expression/result shape or ordinary `do-while`.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `shell language subset lowers control flow scalars strings and raw terminal calls`, `checked in shell
compiles deterministically`, and `while loop jumps lower locally and reject outer targets`.

**Related work:** not scheduled

## Allocation-free `Int` `for` loops

**Status:** Supported.

`start..endInclusive`, `start until endExclusive`, `start..<endExclusive`, `start downTo endInclusive`, and
one positive `step` on those progressions evaluate and snapshot their bounds and step once, then execute as
scalar frame slots with no range, progression, or iterator allocation. Invalid dynamic steps throw a Guest
argument error. Empty, reversed, singleton, negative, `Int.MIN_VALUE`, and `Int.MAX_VALUE` boundaries preserve
Kotlin behavior. `break` and `continue` targeting the current innermost `for` are supported, including nested
loops. Every repeated path crosses an existing loop-header quota safepoint.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `inclusive Int for loops lower without range or iterator allocation`, `exclusive Int for loops lower
without range or iterator allocation`, `Int for loop supplies its generated increment constant`, and
`allocation free Int loops lower deterministically for vm execution` (including descending, stepped, edge, and
invalid-step execution), plus
[`kotlin_writer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs),
test `k2_int_loops_execute_across_quota_slices_without_host_io`.

## Primitive ranges and progressions

**Status:** Supported.

Stored ranges, progression iterators and membership execute through the [range library]({{ '/STDLIB-SUPPORT/arrays-ranges/#ranges-and-progressions' | relative_url }}). Direct `Int` loops retain the
allocation-free lowering described above.

## Remaining loop forms

**Status:** Partial.

Direct primitive-array and supported iterable loops execute. Ordinary source `do-while`, reference-array
iteration, and labeled jumps to an outer loop remain unavailable.

**Evidence:** `unsupported loop forms publish no artifact`.

## Destructuring

**Status:** Partial.

Destructuring through source `component1`/`component2` methods on concrete generic value classes executes in
`testKotlinMfvcVmConformance`. This does not imply support for every generated data-class component method.

## Delegated expressions

**Status:** Unsupported.

Delegated storage remains outside the supported subset.

**Related work:** not scheduled
