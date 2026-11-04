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

## Typed async results

**Status:** Supported.

Top-level `compukter.concurrent.async { ... }` starts cooperative work and returns `Deferred<T>`.
`await()` joins that task, preserving nullable, scalar, Unit and reference results. Repeated awaits return
that same result or rethrow the original task exception. `awaitAll(first, second)` and `awaitAll(list)`
await in argument/list order and return values in the same order. A failed await throws without
cancelling the remaining tasks. These functions use the existing task limits and process lifetime;
returning from main still ends the process and its remaining tasks.

**Evidence:** `MinimalScriptLoweringTest`, test `async results preserve typed values captures and repeated
task failures`, and `testKotlinAsyncVmConformance` execute the actual Guest library through the pinned VM.

## Device command lists

**Status:** Supported for explicitly opted-in immediate Unit commands.

`compukter.commands.Command<Unit>` describes a call without submitting it. Addon device members such
as `engine.setThrottleCommand(0.4)` snapshot their handle and arguments immediately. They are ordinary
members in completion, inside and outside tasks. `batch(commands)` submits the whole list as one
request, applies commands in list order in one server-thread delivery, and returns positional
`CommandResult<Unit>` values. A failed command does not cancel later commands or undo earlier effects.
Use `getOrThrow()` to throw that position's stored `CommandException`, or inspect `isFailure` and
`exceptionOrNull()`. A failed submission itself throws from `batch`.

```kotlin
import compukter.commands.batch
import compukter.concurrent.async

val commands = listOf(left.setThrottleCommand(0.4), right.setThrottleCommand(0.4))
val pending = async { batch(commands) }
val results = pending.await()
for (result in results) result.getOrThrow()
```

Each submission has at most 32 commands and 4096 encoded request/response bytes. The configured
per-tick host-request allowance also counts every nested command (16 by default). A group larger than
that full allowance fails without execution. If only the remainder of the current tick is insufficient,
the whole group waits for the next tick. Synchronous world callbacks may observe intermediate effects;
the group provides neither rollback nor isolation. An empty list performs no host work, duplicate
positions execute separately, and reusing the list executes it again. Closing or losing a device after
description creation is reported when that command executes. Waiting operations and non-Unit methods
are unavailable as batch commands.

**Evidence:** `testKotlinCommandsVmConformance` executes the actual Guest encoder/decoder through the
pinned VM; `CommandBatchProgramHostTest`, `ProgramRuntimeHostTest` and
`GeneratedCommandContractTest` cover ordered failures, validation, quotas and generated SDK dispatch.

## Bounded integer channels

**Status:** Unsupported.

`IntChannel` is removed from the Guest platform in Compukters 0.5.0. Its compiler intrinsics and specialized
channel lowering are removed as well. Generic channels are deferred to a separate design; the current Guest
API exposes no channel type. Cooperative `Task` / `Tasks` and their blocking/join semantics remain supported.

**Evidence:** `MinimalScriptLoweringTest`, test `removed IntChannel is unavailable in the Guest platform`,
and `K2CompilerAdapterTest` verify import rejection and absence from the compiled platform declarations.
Low-level artifact/VM channel instructions retain their existing tests as internal infrastructure; those
instructions do not establish Guest API support.

**Related work:** [#702](https://github.com/CertifiedBadIdeas/Compukters/issues/702)

## Parallel Guest execution

**Status:** Unsupported.

Within a computer, one Guest task at a time executes instructions. Cooperative tasks provide concurrency at suspension
points, not parallel instruction execution.

**Related work:** not scheduled
