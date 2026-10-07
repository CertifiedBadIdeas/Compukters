---
layout: default
title: Performance reports
description: Find reproducible workloads and recorded VM optimization evidence.
section: contributors
permalink: /PERFORMANCE/
---

# Performance reports

Start with [in-world VM benchmarks]({{ '/VM-BENCHMARK/' | relative_url }}) to measure server-tick behavior. Native
interpreter measurements answer different questions: they exclude Minecraft lifecycle and JVM/native transport costs.

## Recorded measurements

| Report | Scope |
| --- | --- |
| [Runtime 0.20.0 baseline, 2026-10-07]({{ '/VM-PERFORMANCE-2026-10-07/' | relative_url }}) | Fresh compiled Guest workloads through the untraced interpreter |
| [VM performance, 2026-10-04]({{ '/VM-PERFORMANCE-2026-10-04/' | relative_url }}) | Recorded hardware and workload baseline |
| [Production-path optimization, 2026-10-04]({{ '/VM-OPTIMIZATION-2026-10-04/' | relative_url }}) | Paired managed type lookup measurements |
| [Virtual dispatch, 2026-10-04]({{ '/VM-DISPATCH-2026-10-04/' | relative_url }}) | Paired per-type dispatch lookup measurements |

## Reproduce a workload

- [Collection loops]({{ '/COLLECTION-BENCHMARK/' | relative_url }}).
- [Collection reuse]({{ '/COLLECTION-REUSE-BENCHMARK/' | relative_url }}).
- [Object collections]({{ '/OBJECT-COLLECTION-BENCHMARK/' | relative_url }}).
- [Object arrays]({{ '/OBJECT-ARRAY-BENCHMARK/' | relative_url }}).
- [Transient allocations]({{ '/TRANSIENT-ALLOCATION-BENCHMARK/' | relative_url }}).

Each report describes its recorded revisions, environment and limits. Compare matched workloads and inspect the
verification evidence before generalizing a timing result to live Minecraft servers.
