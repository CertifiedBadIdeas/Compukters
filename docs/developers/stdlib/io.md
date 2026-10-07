---
layout: default
title: Console I/O and unavailable packages
section: developers
permalink: /STDLIB-SUPPORT/io/
---

# Console I/O and unavailable packages

[← Guest standard library support]({{ '/STDLIB-SUPPORT/' | relative_url }})

* On this page
{:toc}

This is an executable API inventory for the current checkout. [Status and evidence policy]({{ '/STDLIB-SUPPORT/#status-and-evidence' | relative_url }}) applies to every entry. Signatures are indexed in
the [generated Guest API reference]({{ '/guest-api/' | relative_url }}).

### Console functions

**Status:** Partial.

`print` accepts `String`, `Int`, `Long`, `Float`, `Double`, `Boolean`, and `Char`; `println` supports those
types plus the no-argument form; `readln()` reads one canonical line. Other overloads and formatting are
unavailable.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
test `ordinary Kotlin standard streams lower to stdio capability operations`, paired with
[`computer.rs`](https://github.com/CertifiedBadIdeas/Compukter-VM/blob/main/src/computer.rs), tests
`stdio_read_line_echoes_then_writes_stdout_and_stderr_in_order` and
`stdio_read_conflict_becomes_bounded_host_failure_without_consuming_input`.

**Related work:** not scheduled

Although these declarations retain the `kotlin.io` package and default imports, they belong to
`compukter:core`: they depend on the computer environment. Filesystem, process and raw-terminal operations are
[Compukters Guest APIs]({{ '/GUEST-PLATFORM-SUPPORT/' | relative_url }}).

### Reflection and coroutine libraries

**Status:** Unsupported.

These packages have no Guest implementation.

**Related work:** not scheduled

Use [cooperative Guest tasks]({{ '/KOTLIN-SUPPORT/tasks/' | relative_url }}) for supported concurrency and
[nullability and exceptions]({{ '/KOTLIN-SUPPORT/nullability/' | relative_url }}) for catchable operation
errors.
