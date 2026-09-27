# Guest loop and closure allocation benchmark

Run `./gradlew-sandbox-dev-parallel-summary benchmarkTransientAllocations`.
The opt-in generator compiles eight programs with the same 256-element `List<Int>` and 300 summation rounds.
Each program checks the same checksum, 86,957,440, and retains the original list for a final scan.
The harness verifies the artifacts with the Rust VM and separates construction from the repeated operation at
`println("ready")`. It takes three interleaved timing samples and a separate pressure run at a 16 KiB Guest heap.
This is managed Guest heap evidence, not total host process memory per VM.

Sources are retained under `modules/common/compiler-k2/build/generated/benchmarks/transient-allocations`.
Reports are under `build/reports/benchmarks/transient-allocations`.
The measurement artifact removes the compiler's normal 64 KiB admission floor; it does not bypass the Rust verifier.
Fixed-budget runs leave minimum-heap columns empty. `timing_heap_bytes` and `pressure_heap_bytes` distinguish timing
fallback from the requested pressure budget. No fallback was needed in the recorded baseline.

Baseline captured on 2026-09-27, before changing capture-cell selection:
[measurements](benchmarks/transient-allocations-2026-09-27-before.tsv),
[raw samples](benchmarks/transient-allocations-2026-09-27-before-samples.tsv).
All eight checksums passed. `fold-read-var` alone incurred GC work (737 maintenance units); its otherwise identical
`fold-val` control incurred none. The compiler allocated a separate typed cell for the read-only captured `var`.
The mutable control reassigns its captured variable after constructing the lambda and checks that the lambda sees
that new value. This checks aliasing rather than just a numerically equivalent sum.

Instruction and resource units are deterministic VM work counters, not allocation counts or allocated bytes.
Three timing samples are exploratory and insufficient to establish a small CPU speedup. The cases compare ordinary
indexed loops, iterator loops, uncaptured `fold`, scalar captures, shared mutable captures, reference captures, and
reuse of a prebuilt function value. They do not establish that all loops or all collection operations have the same cost.
