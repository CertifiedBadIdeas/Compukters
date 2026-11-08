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

**Status:** Supported for all immediate addon server calls, with typed results.

`compukter.commands.Command<T>` describes a call without submitting it. Generated members such as
`engine.stateCommand()` and `engine.setThrottleCommand(0.4)` capture the handle and arguments immediately;
byte arrays are copied. They appear in completion inside and outside tasks. `batch(commands)` submits one
ordered group and returns immutable `BatchResults<T>`. A homogeneous list preserves its value type during
iteration; mixed commands infer a common type and retain exact types through command lookup. Mixed numeric
results support Number conversions such as `result.getOrThrow().toDouble()` directly in a loop.
Each result belongs to that submission, so repeated submissions do not overwrite earlier results. Only `@GuestWait` operations are excluded.

```kotlin
import compukter.commands.batch
import compukter.concurrent.async

val state = engine.stateCommand()
val throttle = engine.setThrottleCommand(0.4)
val results = async { batch(listOf(state, throttle)) }.await()
val measured = results[state].getOrThrow() // the generated state type
for (result in results) result.getOrThrow() // check every position

val commands = engines.map { it.stateCommand() }
val readings = batch(commands)
for (result in readings) {
    val value = result.getOrThrow() // retains the state type
}
```

`results[command]` matches object identity and requires exactly one occurrence. A different command object
with identical arguments does not match. Repeated objects execute separately; `results.getAll(command)`
returns their typed results in list order (empty when absent). Positional `results[index]` and iteration
retain the list's common result type; exact per-command types use the original command. Command exceptions are retained and
repeated `getOrThrow()` calls throw the same stored object. A failed submission itself throws from `batch`.

Each submission has at most 32 commands and 4096 encoded request/response bytes. Oversized individual replies
become per-position failures while later positions still execute. The per-tick host-request allowance counts
every nested command (16 by default). A group larger than that full allowance fails without execution;
insufficient remaining tick credits defer the group intact. Failures neither cancel later calls nor undo
earlier effects. Synchronous world callbacks may observe intermediate effects; there is no rollback or isolation.
An empty list performs no host work. Closing or losing a device after capture is reported on execution.

Command capability ABI 2 replaces the earlier Unit-only protocol; programs using old batches must be recompiled.

**Evidence:** `testKotlinCommandsVmConformance` executes the actual Guest codec through the pinned VM;
`DeclarativeNativeIntegrationTest` covers typed mixed results, records, iteration, repeated commands and identity lookup.
`CommandBatchCodecTest`, `CommandBatchProgramHostTest`, `ProgramRuntimeHostTest` and generated host tests
cover bounded replies, ordered independent failures, validation, quotas and SDK dispatch.

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
