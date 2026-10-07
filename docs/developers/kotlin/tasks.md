---
layout: default
title: Tasks and concurrency
section: developers
permalink: /KOTLIN-SUPPORT/tasks/
---

# Tasks and concurrency

[← Guest Kotlin support]({{ '/KOTLIN-SUPPORT/' | relative_url }})

* On this page
{:toc}

This page describes the current checkout, including unreleased work. [Status and evidence policy]({{ '/KOTLIN-SUPPORT/#status-and-evidence' | relative_url }}) applies to every entry.

## Transparent suspension across a host request

**Status:** Supported.

An ordinary Guest call preserves its complete stack and resumes at its verified continuation after an
asynchronous capability response.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
test `ordinary project call resumes transparently across host blocking`, and
[`kotlin_writer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs),
test `k2_ordinary_project_call_resumes_across_async_capability`.

## VM-blocking calls from ordinary functions

**Status:** Supported.

Designated Guest API calls, task joins, and channel handoffs block only the current stackful VM task. Ordinary
callers need no source modifier, including across nested project calls.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `ordinary main lowers trusted terminal wait as vm blocking` and `ordinary project call resumes
transparently across host blocking`, paired with
[`verify/tests.rs`](https://github.com/CertifiedBadIdeas/Compukter-VM/blob/main/src/verify/tests.rs), tests
`vm_blocking_capability_is_valid_in_a_non_suspending_function`,
`task_yield_is_valid_in_a_non_suspending_function`, and `task_spawn_accepts_a_non_suspending_target`.

## Kotlin `suspend` declarations

**Status:** Unsupported.

Guest tasks use transparent stackful suspension instead of Kotlin's coroutine effect and calling convention.
The compiler and IDE reject `suspend` source while the artifact reader, verifier, and VM retain legacy
suspend-call support.

**Evidence:** `MinimalScriptLoweringTest`, test `suspend declarations are rejected because Guest tasks suspend
transparently`, and `DiagnosticQueryTest`, test `suspend declaration is outside the transparent Guest task
model`.

## Cooperative Guest tasks

**Status:** Partial.

`Tasks.launch(block)` starts any supported non-null `() -> Unit` value as a bounded task, `Task.join()` waits
for it, and `Tasks.sleepTicks(n)` suspends the current task until a deterministic server-tick boundary without
consuming Guest instructions. Tasks share one VM and execute one at a time, but a task suspended on host I/O
does not stop another runnable task. Scheduling and host-request ownership are deterministic. Failed-task
joins rethrow the original exception on every join; an unjoined child failure does not terminate the process.
Failure retention ends with the process. Public cancellation, explicit same-turn yield, wall-clock delay,
scopes, and `kotlinx.coroutines` remain unsupported.

**Evidence:** `MinimalScriptLoweringTest`, test `task tick sleep lowers to one asynchronous timer request`,
the `testKotlinTimerVmConformance` task, and `ProgramRuntimeHostTest`, test `timer requests resume on
deterministic server tick boundaries`.

**Related work:** [#624](https://github.com/CertifiedBadIdeas/Compukters/issues/624)

## Bounded integer channels

**Status:** Partial.

A top-level `IntChannel(capacity)` provides deterministic FIFO `send(Int)` and `receive(): Int` blocking
handoff between cooperative tasks. Capacity must be a positive compile-time constant; channel storage and
waiter state are admitted up front and communication stays inside the VM without a host request. Generic
payloads, close, cancellation, selection, timeouts, and cross-process channels remain unsupported.

**Evidence:**
[`channel.rs`](https://github.com/CertifiedBadIdeas/Compukter-VM/blob/main/src/execution/channel.rs),
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
and the `testKotlinChannelVmConformance` task.

**Related work:** [#614](https://github.com/CertifiedBadIdeas/Compukters/issues/614)

## Parallel Guest execution

**Status:** Unsupported.

Within a computer, one Guest task at a time executes instructions. Cooperative tasks provide concurrency at suspension
points, not parallel instruction execution.

**Related work:** not scheduled
