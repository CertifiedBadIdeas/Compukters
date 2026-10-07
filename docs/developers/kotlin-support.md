---
layout: default
title: Guest Kotlin support
section: developers
permalink: /KOTLIN-SUPPORT/
---

# Guest Kotlin support

Compukters compiles Guest Kotlin through a pinned K2 frontend into verified Compukter bytecode for the managed Rust VM. Guest name resolution contains only the selected native platform declarations; host Kotlin/JVM dependencies are unavailable. K2 acceptance alone does not establish executable support.

The pages below describe the repository revision that contains them, including unreleased work. Each feature has one detailed owner and its supporting evidence.

## Language support

| Topic | Contents |
| --- | --- |
| [Entry points and projects](kotlin/projects.md) | Legal main forms, source projects and platform selection |
| [Types and numeric semantics](kotlin/primitives.md) | All twelve primitive families, boxing, operators and conversion |
| [Expressions and control flow](kotlin/control-flow.md) | Branches, loops, local exits and destructuring |
| [Functions and calls](kotlin/functions.md) | Defaults, generics, inline calls, closures and references |
| [Classes and object model](kotlin/objects.md) | Construction, accessors, dispatch, casts and MFVC |
| [Nullability and exceptions](kotlin/nullability.md) | Nullable storage, safe calls, assertions, handlers and operation errors |
| [Tasks and concurrency](kotlin/tasks.md) | Transparent blocking, cooperative tasks and channels |

## Libraries, devices and tooling

- [Standard library](stdlib-support.md) — callable Kotlin APIs, arrays, ranges and collections.
- [Guest platform and device APIs](guest-platform-support.md) — native bindings, peripheral providers and addon contracts.
- [IDE and compiler tooling](ide-support.md) — diagnostics, completion, navigation, formatting and deployment.

## Status and evidence

- [x] **Supported** — the narrowly stated behavior has execution-level
  conformance evidence, or a focused tooling test for an IDE-only claim.
- [ ] **Partial** — a useful subset works, but the stated boundary remains.
- [ ] **Unsupported** — the backend deliberately rejects the construct or has
  no implementation for it.
- **Not planned** — an intentional platform boundary, not queued work.

Every supported item names its evidence. Unchecked work links an exact tracking
issue when scheduled; otherwise it says `Tracking: not scheduled`. Compiler
acceptance alone is not execution evidence: a VM operation and a K2 construct
must meet through conformance coverage before the construct is marked
supported.

## Intentional non-goals

- **Not planned: Java interoperability and JVM bytecode/libraries.** Guest
  programs target Compukter bytecode, not a JVM.
- **Not planned: reflection and dynamic class loading.** Runtime types and code
  are admitted from verified artifacts before execution.
- **Not planned: arbitrary compiler plugins and annotation processors.** The
  trusted compiler pipeline and selected native platform modules define the
  source surface.
- **Not planned: ambient access to host JVM or operating-system resources.**
  Guest programs cross only explicit, bounded capability interfaces.

## Maintenance policy

Update the owning topic when changing Guest behavior; update a cross-topic summary only when its boundary changes. Do not copy an API inventory into the language pages. The [generated API reference]({{ '/guest-api/' | relative_url }}) owns signatures; support pages own executable behavior, limitations and evidence.

- A commit that changes Guest Kotlin support updates the affected topic entry
  and its evidence in the same commit.
- Every supported item keeps a stable repository link and names the exact test
  behavior that supports it.
- A scheduled gap links its exact implementation issue; broad umbrella issues
  do not replace feature-specific tracking.
- Removing support marks the entry unsupported and states the new boundary in the same
  change.
- Intentional non-goals change only through an explicit architecture decision,
  not by converting them into unchecked tasks.
- These pages carry no manually maintained release number or commit hash;
  they always describe the revision that contains them.

## Topic shortcuts

<a id="entry-points-and-projects"></a>

[Entry points and projects](kotlin/projects.md)

<a id="types-and-numeric-semantics"></a>

[Types and numeric semantics](kotlin/primitives.md)

<a id="expressions-and-control-flow"></a>

[Expressions and control flow](kotlin/control-flow.md)

<a id="functions-and-calls"></a>

[Functions and calls](kotlin/functions.md)

<a id="classes-and-object-model"></a>

[Classes and object model](kotlin/objects.md)

<a id="tasks-and-concurrency"></a>

[Tasks and concurrency](kotlin/tasks.md)

<a id="strings-arrays-and-collections"></a>

[Strings, arrays, and collections]({{ '/STDLIB-SUPPORT/' | relative_url }})

<a id="built-in-guest-platform"></a>

[Built-in Guest platform]({{ '/GUEST-PLATFORM-SUPPORT/' | relative_url }})

<a id="kotlin-standard-library"></a>

[Kotlin standard library]({{ '/STDLIB-SUPPORT/' | relative_url }})

<a id="compukters-guest-apis"></a>

[Compukters Guest APIs]({{ '/GUEST-PLATFORM-SUPPORT/' | relative_url }})

<a id="ide-and-tooling"></a>

[IDE and tooling]({{ '/IDE-SUPPORT/' | relative_url }})

<a id="nullability-and-exceptions"></a>

[Nullability and exceptions](kotlin/nullability.md)

<a id="status-legend"></a>

[Status legend]({{ '/KOTLIN-SUPPORT/#status-and-evidence' | relative_url }})

For agents and maintainers: follow the relative document links above, read the relevant entry and its evidence, and update that owning file in the same commit as the implementation. These index files retain the shared policy; topic pages retain the detailed contracts.
