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
`continue` target either the innermost loop or an active enclosing labeled loop. There is no source-level
conformance suite covering every expression/result shape.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `shell language subset lowers control flow scalars strings and raw terminal calls`, `checked in shell
compiles deterministically`, and `while loop jumps lower locally`.

**Related work:** not scheduled

## Allocation-free `Int` `for` loops

**Status:** Supported.

`start..endInclusive`, `start until endExclusive`, `start..<endExclusive`, `start downTo endInclusive`, and
one positive `step` on those progressions evaluate and snapshot their bounds and step once, then execute as
scalar frame slots with no range, progression, or iterator allocation. Invalid dynamic steps throw a Guest
argument error. Empty, reversed, singleton, negative, `Int.MIN_VALUE`, and `Int.MAX_VALUE` boundaries preserve
Kotlin behavior. Local and labeled `break` and `continue` are supported, including nested loops. Every
repeated path crosses an existing loop-header quota safepoint.

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

## Post-test `do-while` loops

**Status:** Supported.

Ordinary `do-while` executes its body before testing the condition. Each local `continue` runs intervening
`finally` cleanup and reaches the condition; local `break` and function return run cleanup and skip the
condition. Body-local declarations retain Kotlin's condition scope. Nested loops select their own local
targets. Exceptions from the body or condition use ordinary managed handlers, and loop execution resumes
across instruction-budget slices. Labeled jumps preserve these condition and cleanup rules.

**Evidence:** `MinimalScriptLoweringTest`, test `do while loops preserve post test order jumps cleanup and
exceptions for vm execution`, executed by `testKotlinIntLoopsVmConformance` with instruction budgets of 64
and 512. `DiagnosticQueryTest`, test `do while analysis admits body scoped conditions and local cleanup jumps`,
checks source-attached and metadata-only IDE sessions.

**Related work:** [#737](https://github.com/CertifiedBadIdeas/Compukters/issues/737)

## Labeled loop jumps

**Status:** Supported.

`break@label` and `continue@label` resolve to an active enclosing loop by K2 IR identity, including nested
`while`, `do-while`, optimized `Int` progression loops, primitive and reference-array loops, and admitted `Iterable` loops.
A break exits that loop; a continue reaches its condition, progression increment, array index advancement,
or iterator check. Reused label names resolve to the nearest matching loop according to Kotlin scope.

Only crossed `finally` scopes run, from the innermost outward; cleanup surrounding the target loop stays
active. A return, another loop jump, or exception from cleanup overrides the pending jump. This adds no
instructions or ABI changes and does not widen jumps across function or inline-lambda boundaries. Invalid
or out-of-scope labels produce diagnostics without publishing an artifact.

**Evidence:** `MinimalScriptLoweringTest`, tests `labeled loop jumps preserve target advancement and cleanup
for vm execution` (deterministic artifact) and `invalid loop labels publish no artifact`.
`testKotlinIntLoopsVmConformance` requires the labeled-loop artifact and executes its assertions at instruction
budgets of 64 and 512. `DiagnosticQueryTest`, test `labeled loop analysis resolves mixed targets with source
and metadata inputs`, checks both IDE input forms.

**Related work:** [#738](https://github.com/CertifiedBadIdeas/Compukters/issues/738)

## Direct reference-array loops

**Status:** Supported for admitted element representations.

`for (element in array)` traverses `Array<T>` in ascending index order without allocating an iterator.
Concrete non-null primitive element specializations load dense scalars directly; nullable/reference and
value-class elements retain their existing representation.
The source expression and array reference evaluate once; reassigning a source variable does not switch the
traversed array. Writes to that array's later slots remain visible. An empty array does not execute the body.

Elements use the same representations as indexed reads: strings, source/imported references, `Any`, value
classes (including multi-field values), nullable elements and nullable primitive boxes, with concrete generic
specialization. Non-null value classes unbox into local values; nullable ones retain their managed boxes.
Local and labeled jumps, `finally` cleanup, managed exceptions and quota safepoints follow the other array
loops. Stored array iterators remain unavailable;
see the [array library](../stdlib/arrays-ranges.md) for storage boundaries.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `reference array loops preserve specialized elements source
identity and cleanup for vm execution` (deterministic artifact) and
`guest arrays reject stored iterators spread and nested storage`. `testKotlinReferenceArrayVmConformance`
requires this loop artifact and executes
its assertions at budgets 64 and 512 with a 256 KiB heap, including allocation pressure and retained references.
`DiagnosticQueryTest`, test `reference array loops resolve nullable generic and value class elements with
source and metadata inputs`, checks both IDE input forms.

**Related work:** [#741](https://github.com/CertifiedBadIdeas/Compukters/issues/741)

## Destructuring

**Status:** Partial.

Destructuring through source `component1`/`component2` methods on concrete generic value classes executes in
`testKotlinMfvcVmConformance`. This does not imply support for every generated data-class component method.

## Delegated expressions

**Status:** Unsupported.

Delegated storage remains outside the supported subset.

**Related work:** not scheduled
