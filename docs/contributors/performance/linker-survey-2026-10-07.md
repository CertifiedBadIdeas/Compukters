---
layout: default
title: Linked methods and devirtualization opportunities (2026-10-07)
section: contributors
permalink: /VM-LINKER-SURVEY-2026-10-07/
---

# Linked methods and devirtualization opportunities

Compiler-side investigation found a concrete next speed experiment: direct calls to known final methods while
retaining their virtual declarations. Method pruning has useful size potential for some specialized collection
programs, but little absolute benefit for the builtins measured here. These are static inventories and models;
no compiler/runtime behavior or artifact bytes changed, and no speedup or achieved size reduction is claimed.

Parent revision: `d2afe97e`; VM: `bac6789`. The eight compact builtin/conformance artifacts are the outputs measured
in the [root ranges report]({{ '/VM-ROOT-RANGES-2026-10-07/' | relative_url }}). Four frozen benchmark inputs come from
the [October 7 baseline]({{ '/VM-PERFORMANCE-2026-10-07/' | relative_url }}); their legacy debug/root encoding makes
byte estimates incomparable with the compact outputs. No artifacts were regenerated for this investigation.

## What currently retains methods

`compiler-artifact`'s `ReachabilityGraph.visitType` marks the complete method range of every reachable class/interface.
`visitFunction` marks its owner, signatures, values, blocks and debug entries. Consequently, referencing a type also
keeps methods whose bodies are never called, along with dependencies of those bodies. Basic function dead-code
elimination already exists; the opportunity is more precise method reachability.

This retention also satisfies an admission invariant. Native `resolve_dispatch_entries` considers every retained
virtual method and interface-owned declaration, not only declarations referenced by call instructions. For each
concrete compatible type it requires a resolvable implementation. Removing an unused implementation while keeping
its interface declaration can therefore make an artifact fail admission.

The current linker relocates `methodStart` but leaves `methodCount` intact. Selective pruning must rebuild both,
retain required implementations/defaults/bridges, relocate references and recompute module identities. Public
library fragment assembly (`preserveLibraryExports=true`) must retain its complete exported callable surface.
Optimization belongs in final executable linking, after specialization ownership/reuse has been established.

## Modeled size opportunity

The read-only analyzer models a conservative function closure. It retains the entry and every class initializer,
follows direct/suspend/task calls, preserves inline debug parent owners, and overapproximates dynamic implementations
by method name and parameter count across all modules. Even dynamic sites in otherwise dead bodies contribute.
Whenever a retained virtual/interface declaration appears, all matching name/arity candidates remain retained.
The model intentionally ignores receiver ancestry and detailed signature discrimination, retaining extra functions.

Two variants are archived. Keeping *all existing dispatch declarations* finds no removable builtin functions,
17 mapNotNull functions (2841 owned bytes), and two mutableList functions (338 bytes). Allowing unreferenced dispatch
declarations to disappear produces the following candidate inventory:

| Artifact | Functions now | Functions outside model closure | Their owned records/indexes, KiB | Fraction of current file |
| --- | ---: | ---: | ---: | ---: |
| `boot` | 21 | 2 | 0.52 | 1.48% |
| `edit` | 53 | 4 | 1.05 | 0.70% |
| `kotlinc` | 21 | 6 | 1.62 | 4.84% |
| `shell` | 43 | 2 | 0.52 | 0.45% |
| `vmbench-agent` | 20 | 6 | 1.67 | 5.26% |
| `vmbench` | 32 | 6 | 1.67 | 4.43% |
| `kotlin-map-not-null` | 582 | 156 | 70.24 | 19.34% |
| `kotlin-mutable-list` | 407 | 8 | 2.00 | 0.76% |

These columns count existing function, block, code, exception, root, debug and source-position records plus their
index entries. They do not predict the final rewritten artifact size: shared strings/types/constants/imports/paths,
section headers, alignment and further reachability changes are excluded. The model is not a replacement for
implementing and verifying a linker transformation, and the percentage is not an achieved saving or strict bound.

In mapNotNull, 150 candidates belong to `app` and six to `kotlin.Any`; specialized standard-library bodies reside in
the application module. Removing only independently linked library modules misses most of this opportunity.
Candidate records include unused `lastIndexOf`, `remove`, `clear`, `contains` implementations/declarations and
associated specializations. Of the 71927 owned bytes, CODE accounts for 8591; function/block/root/debug metadata
accounts for most of the rest. Most builtin candidates are unused `Any.toString`/`hashCode` bodies/declarations.
MutableList exercises a much broader method surface, so this conservative model offers little pruning there.

## Direct calls to final implementations

K2 lowering selects virtual calls for class methods when the target is non-final **or has overridden symbols**.
The same condition appropriately retains the function's VIRTUAL flag for calls through a parent declaration.
However, call-site selection can separately use a known final target; overriding a parent does not itself imply
that a specific source call needs dynamic lookup. Interface-typed calls require a separate proof of their receiver.

| Input | Virtual sites | Interface sites | Dynamic sites with concrete final target owner and non-null receiver metadata |
| --- | ---: | ---: | ---: |
| `kotlin-map-not-null` | 254 | 181 | 228 |
| `kotlin-mutable-list` | 199 | 120 | 184 |
| `while-indexed-256-300` | 36 | 29 | 32 |
| `for-list-256-300` | 36 | 30 | 32 |
| `fold-256-300` | 36 | 30 | 32 |
| `map-not-null-1024-100-fresh` | 43 | 34 | 37 |

All six builtins have zero sites matching this specific condition. Collection candidates predominantly target
`ArrayList` and iterator methods such as `get`, `size`, `add`, `next`, `iterator`, searches and removal. Counts include
cold and potentially unreachable bodies; they are **static instruction sites**, not runtime invocation frequencies
or projected speedup. The final-receiver model finds the same counts on these inputs, without extra interface sites.

The indexed hot fixture declares `val data: List<Int> = storage`. Its hot reads remain interface calls despite the
known original ArrayList allocation. Removing those dispatches needs receiver provenance analysis through aliases/
casts, beyond the narrow final-target experiment. The final-owner count alone does not quantify the hottest work.

A first implementation should change call-site selection while retaining VIRTUAL flags, preserve argument evaluation,
null/invalid-reference behavior, `super` calls, overrides and interface defaults, and respect fake-override targets and
library imports. Non-null metadata is a candidate filter, not a sufficient runtime-validity proof. Direct calls to
concrete virtual functions already pass the native verifier; no new opcode is needed for that narrow experiment.

Direct call fixed cost is `4 + argument count`, virtual `5 + argument count`, interface `6 + argument count`.
Thus compiler optimization can legitimately change guest fixed-unit counters and slice boundaries. Future paired
runs must use identical source inputs, compare checksums/semantics and explain expected counter changes rather than
requiring every work counter to equal the old artifact. CPU/wall timing should use the
[updated benchmark harness]({{ '/VM-CPU-TRIALS-2026-10-07/' | relative_url }}), with before/after compiler outputs and
unchanged VM revision recorded separately.

## Recommendation and evidence

Start with the localized final-target lowering experiment and focused dispatch, generic-library, collection and
null/super/default-method conformance. Measure construction and hot CPU time on regenerated matched sources; the
previous interpreter-only experiments show that fewer apparent lookups do not guarantee a speedup. More precise
method pruning is a separate coordinated compiler/linker investigation, particularly useful for mapNotNull-like
specializations. Neither result establishes behavior for the unavailable drone PID controller.

The new `docs/_tools/analyze_linked_calls.py` checks container digest/bounds, indexed sections, call operand framing,
block ownership/counts, function layouts and import/export resolution. All 12 recorded inputs also passed the native
public artifact verifier. Inventory hashes/totals, candidate count bounds and payload sums agree; repeat runs
reproduce the archive. Manual negative checks reject a damaged digest and an invalid instruction frame with a
recomputed digest. No Gradle, VM execution, Minecraft or release checks are claimed for this read-only research.

[Compact artifact inventory]({{ '/benchmarks/linker-survey-2026-10-07/inventory.json' | relative_url }}),
[frozen benchmark inventory]({{ '/benchmarks/linker-survey-2026-10-07/benchmark-inventory.json' | relative_url }}),
[revisions, hashes and exact commands]({{ '/benchmarks/linker-survey-2026-10-07/provenance.json' | relative_url }}),
[verification evidence]({{ '/benchmarks/linker-survey-2026-10-07/verification.txt' | relative_url }}).

Reproduce with `python3 docs/_tools/analyze_linked_calls.py ARTIFACT...` on the recorded final executable inputs.
