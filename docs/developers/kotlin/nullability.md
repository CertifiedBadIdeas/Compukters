---
layout: default
title: Nullability and exceptions
section: developers
permalink: /KOTLIN-SUPPORT/nullability/
---

# Nullability and exceptions

[← Guest Kotlin support]({{ '/KOTLIN-SUPPORT/' | relative_url }})

* On this page
{:toc}

This page describes the current checkout, including unreleased work. [Status and evidence policy]({{ '/KOTLIN-SUPPORT/#status-and-evidence' | relative_url }}) applies to every entry.

## Nullable user references

**Status:** Supported.

`String?` and supported Guest class references can be local values, top-level immutable properties, class
fields, function parameters, and results. `null` can initialize these values or be passed and returned in a
typed reference context. Nullable value classes use the [nominal managed wrappers](objects.md) for their
layouts; all twelve nullable primitives retain their [distinct nominal boxes](primitives.md). Nullable
function values remain outside this subset.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `nullable references lower null comparisons Elvis and reference safe calls` and `unsupported nullable
forms do not publish artifacts`, paired with
[`kotlin_writer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs),
scenario `nullable-references`.

**Related work:** [#654](https://github.com/CertifiedBadIdeas/Compukters/issues/654)

## Boxed nullable `Int`

**Status:** Supported.

`Int?` stores a managed Int box or null in locals, fields, parameters, and results. Assigning `Int` boxes at
the nullable boundary; widening to `Any?` preserves the reference. Checked casts and Elvis recover unboxed Int
values. Equality compares boxed payloads and treats null according to Kotlin semantics.

**Evidence:** `MinimalScriptLoweringTest`, test `nullable Int and collection elements preserve values and
nulls`, executed by `testKotlinNullableCollectionsVmConformance`.

**Related work:** [#657](https://github.com/CertifiedBadIdeas/Compukters/issues/657)

## Safe calls and Elvis

**Status:** Partial.

Nullable references can be compared with `null`, selected with `?:`, and accessed with `?.` when the result is
a supported reference type. Both operators evaluate the left side once and skip the unused branch. Safe calls
with an Int result, such as `text?.length`, produce a boxed `Int?`. Nullable primitive results use their
distinct nominal scalar wrappers; non-null assertions (`!!`) on nullable value classes recover the direct
layout and are exercised by `testKotlinMfvcVmConformance`. Statically-null and nullable-function shapes are
still rejected by the focused negative fixture. Int-result safe-call evidence:
`testKotlinNullableCollectionsVmConformance`. Reference-result evidence:
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `nullable references lower null comparisons Elvis and reference safe calls` and `unsupported nullable
forms do not publish artifacts`, paired with
[`kotlin_writer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs),
scenario `nullable-references`.

**Related work:** [#654](https://github.com/CertifiedBadIdeas/Compukters/issues/654)

## Explicit `throw`, `try`, `catch`, and `finally`

**Status:** Supported.

Explicit statement `throw`, typed `try/catch` statements and expressions, nested handlers and rethrow preserve
managed exception identity through Guest calls. Catch matches the real class hierarchy, including user
subclasses. The VM retains child-task failures for repeated joins and roots exceptions through GC. `finally`
runs on normal and exceptional exits, return, supported break/continue and inline returns, and can replace a
pending return or exception. Return values are evaluated before cleanup. Cleanup throws are outside the
handlers of the try being exited. Legacy exception artifacts must be rebuilt for Runtime ABI 1.8; there is no
terminal-trap fallback.

**Evidence:** `MinimalScriptLoweringTest`, test `explicit exception preserves class and message for vm
execution`, and `testKotlinExceptionsVmConformance`; source catch evidence: `caught exception preserves
identity hierarchy cause and expression result for vm execution`; finally evidence: `finally preserves normal
exceptional and nonlocal exits for vm execution` in the same Kotlin-to-VM conformance task, including nested
cleanup, local versus nonlocal inline returns and return snapshots; task/suspension evidence: `exceptions
preserve failed task joins and cleanup across suspension for vm execution` checks repeated joins preserve
exception identity, an unjoined failed child does not stop healthy tasks, and cleanup runs after resuming host
input; native tests `explicit_exceptions_preserve_identity_across_calls_and_repeated_task_joins` and
`exception_handlers_choose_innermost_region_and_source_order_and_rethrow_to_outer`.

**Related work:** [#677](https://github.com/CertifiedBadIdeas/Compukters/issues/677)

## Supported exception classes and operation errors

**Status:** Supported.

Guest `Throwable`, `Exception`, `RuntimeException`, `IllegalArgumentException`, `IllegalStateException`,
`ArithmeticException`, `IndexOutOfBoundsException`, `NegativeArraySizeException`, `NullPointerException`,
`ClassCastException`, `NoWhenBranchMatchedException` and `compukter.io.IOException` have distinct nominal
identities. Message/cause constructors and read-only properties. Only `Throwable` uses trusted external
constructors; standard descendants have ordinary Kotlin primary constructors compiled in their owning library,
with `cause = null` when omitted. User exception constructors initialize inherited payload through ordinary
super calls. Uncaught exceptions report class, message and bounded source stack through both FFM and JNI.
Int/Long division and remainder by zero throw catchable `ArithmeticException` through ordinary calls and
finally, with nullable cause and the message `/ by zero`. Runtime ABI 1.9 retains its verified factory type
even without a source catch. Array/string bounds, negative array sizes, null reference operations and checked
casts also throw typed managed exceptions; invalid channel arguments throw IllegalArgumentException. Artifacts
containing these fallible operations require rebuilding for ABI 1.9. Host EOF/I/O failures throw IOException;
unavailable/other failures throw IllegalStateException at the original call site. Cancellation, OOM, quotas
and VM faults remain noncatchable.

**Evidence:** real native transport tests `FFM preserves uncaught exception class message and source stack`
and `JNI preserves uncaught exception class message and source stack`. Arithmetic evidence: `integer
arithmetic errors are catchable across calls and preserve finally for vm execution` in
`testKotlinExceptionsVmConformance`; native tests
`arithmetic_factory_respects_single_unit_slices_and_roots_its_unpublished_payload` and
`arithmetic_factory_allocation_failure_remains_noncatchable`. Constructor evidence: `caught exception
preserves identity hierarchy cause and expression result for vm execution` in
`testKotlinExceptionsVmConformance`, including library construction, named argument order, inherited cause
identity, omitted cause and user-class initialization through an IOException subclass. Operation evidence:
`array string null and cast errors are catchable with cleanup for vm execution` in
`testKotlinExceptionsVmConformance`, native atomicity/exception-message tests in `heap_tests` and
`text_tests`, and `fallible_operations_reject_each_missing_factory_and_legacy_abi`. Host evidence: `host
failures preserve task identity call sites and cleanup for vm execution` in
`testKotlinExceptionsVmConformance`, including reversed responses for two tasks and missing-file I/O; native
tests `ordinary_host_failures_raise_at_the_original_call_without_double_retirement` and
`host_exception_factory_oom_remains_noncatchable`.

**Related work:** [#677](https://github.com/CertifiedBadIdeas/Compukters/issues/677)
