# Runtime VM Profiling Report Task Design

## Goal

Add one Gradle task that runs the existing terminal/display profiling workloads with both VM runners and writes a human-readable Markdown comparison report.

## Scope

The task should:

- build the local Rust JNI library before Rust profiling;
- run the profiling workloads for the default Kotlin/JVM VM runner;
- run the same workloads for the Rust VM runner;
- collect display, runtime, compiler, queue, host-call, and instruction metrics;
- write a Markdown report under `build/reports/profiling/`;
- keep normal test tasks unchanged.

Out of scope:

- release-mode Rust profiling;
- external profiler integration;
- native instruction-level Rust metrics, unless added separately later;
- changing VM semantics or workload behavior.

## Approach

Use a dedicated report-only JUnit test plus a Gradle task:

1. Extract the shared workload runner from `RuntimeDisplayProfilingTest` into a test-scope helper that can be reused by both assertions and report generation.
2. Add `RuntimeVmProfilingReportTest`, which switches `ckl.vm.runner` between `kotlin` and `rust`, runs the workloads, and writes a Markdown comparison report.
3. Add `profileRuntimeVmComparison` to the NeoForge module. It depends on `buildRustVmNativeLibrary`, runs only `RuntimeVmProfilingReportTest`, passes `ckl.vm.native.library`, and prints the report location.

## Report Content

The report should include:

- command/context metadata;
- per-workload comparison tables for JVM and Rust;
- ratios for key metrics;
- notable host-call comparisons;
- interpretation notes, including that Rust currently does not expose per-instruction metrics.

## Error Handling

If the Rust native library is missing, the Gradle task builds it first. If Rust profiling fails, the task should fail because the report would be misleading.

## Testing

- Add unit coverage for Markdown report formatting/parsing helpers.
- Run the new Gradle task and confirm the Markdown report exists.
- Run existing profiling tests to ensure their assertions still pass for the default JVM runner.
