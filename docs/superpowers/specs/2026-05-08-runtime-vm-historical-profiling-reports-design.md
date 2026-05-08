# Runtime VM Historical Profiling Reports Design

## Goal

Restore human-readable Markdown profiling reports and make runtime VM profiling comparable across all saved profiling runs, not only one current raw TSV file.

## Current State

`profileRuntimeVmImage` writes one stable raw profile:

```text
modules/v1_21_1/v1_21_1-neoforge/build/reports/profiling/runtime-vm-image.tsv
```

Older commits had a Markdown formatter and comparison aggregation task, but those compared the removed JVM runner with the Rust runner. The current runtime is Rust image-only, so the comparison axis should be historical runs.

## Design

Each `profileRuntimeVmImage` execution writes:

- the stable raw TSV file at `runtime-vm-image.tsv`;
- a timestamped archive directory under `reports/profiling/runs/<timestamp>/`;
- the archived `runtime-vm-image.tsv`;
- a per-run `runtime-vm-image.md` Markdown summary.

The timestamp uses a filesystem-safe ISO-like local offset format such as `2026-05-08T14-37-12+03-00`.

`profileRuntimeVmComparison` scans every archived `runs/*/runtime-vm-image.tsv`, reads all profiles, sorts them by timestamp, and writes:

```text
modules/v1_21_1/v1_21_1-neoforge/build/reports/profiling/runtime-vm-comparison.md
```

The comparison report includes every workload found in any archived run. It does not hard-code workload names. It also builds host-call tables from the union of host-call keys found across all runs, so newly added profiling workloads and host calls appear automatically.

## Report Shape

The per-run report contains:

- report title and run metadata;
- one section per workload;
- summary metrics for display, runtime, VM, host calls, compiler, and held-Enter metrics when present;
- host-call details sorted by total time.

The historical report contains:

- report title and list of archived runs;
- one section per workload present in any run;
- tables where rows are runs and columns include timestamp, runtime, commit when available, value, and ratio versus the previous run;
- host-call tables using the union of host-call keys for that workload across all runs.

## Metadata

Each archived run stores metadata in memory for formatting and can also write a simple metadata file later if needed. The first implementation records timestamp and runtime name. Git branch/commit can be added from Gradle properties without changing TSV compatibility.

## Compatibility

`RuntimeVmProfileCodec` remains backward compatible with existing raw TSV files. The stable `runtime-vm-image.tsv` path remains available for scripts. Historical aggregation ignores malformed or incomplete run directories by failing the aggregation test, because silent omissions would make comparison reports misleading.
