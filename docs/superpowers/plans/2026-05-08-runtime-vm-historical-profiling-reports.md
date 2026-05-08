# Runtime VM Historical Profiling Reports Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Archive every runtime VM profiling run with a timestamp and generate Markdown reports over all archived runs.

**Architecture:** Keep TSV as the raw compatibility format. Add small metadata wrappers and Markdown formatter helpers in `RuntimeVmProfilingReport.kt`, then wire `RuntimeVmProfilingReportTest` to write both stable and timestamped files. Reintroduce an aggregation test and Gradle task that scans `reports/profiling/runs/*/runtime-vm-image.tsv`.

**Tech Stack:** Kotlin/JVM, JUnit 5, Gradle Kotlin DSL, existing runtime/display/compiler profiling collectors.

---

### Task 1: Formatter Tests

**Files:**
- Create: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt`

- [ ] **Step 1: Write a failing test for historical Markdown**

Create two synthetic `RuntimeVmProfileRun` values with different timestamps. Assert that the Markdown includes both timestamps, every workload name, a ratio versus previous run, and host-call keys from both runs.

- [ ] **Step 2: Run the formatter test and verify RED**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests '*RuntimeVmProfilingReportFormatterTest' --rerun-tasks
```

Expected: fail because formatter/run metadata types do not exist.

### Task 2: Formatter Implementation

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt`

- [ ] **Step 1: Add run metadata types**

Add `RuntimeVmProfileRun` and `RuntimeVmProfileRunMetadata`.

- [ ] **Step 2: Add Markdown formatter**

Implement per-run and historical Markdown formatting. Historical formatting must derive workloads and host-call keys from data, not hard-coded names.

- [ ] **Step 3: Run formatter test and verify GREEN**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests '*RuntimeVmProfilingReportFormatterTest' --rerun-tasks
```

Expected: pass.

### Task 3: Archive and Aggregation Tests

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportTest.kt`
- Create: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportAggregationTest.kt`

- [ ] **Step 1: Write archive behavior test**

Extend the report test path behavior so profiling task properties can provide a runs directory and timestamp.

- [ ] **Step 2: Write aggregation behavior test**

Create temporary `runs/<timestamp>/runtime-vm-image.tsv` fixtures, run aggregation, and assert that the Markdown includes both runs.

- [ ] **Step 3: Run tests and verify RED/GREEN through implementation**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests '*RuntimeVmProfilingReportTest' --tests '*RuntimeVmProfilingReportAggregationTest' --rerun-tasks
```

Expected: pass after implementation.

### Task 4: Gradle and Docs

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts`
- Modify: `build.gradle.kts`
- Modify: `docs/PROFILING.md`

- [ ] **Step 1: Wire `profileRuntimeVmImage` archive properties**

Pass stable TSV, runs directory, Markdown path, and a generated timestamp into `RuntimeVmProfilingReportTest`.

- [ ] **Step 2: Restore module `profileRuntimeVmComparison`**

Add a task that depends on `profileRuntimeVmImage`, runs `RuntimeVmProfilingReportAggregationTest`, scans all archived runs, and writes `runtime-vm-comparison.md`.

- [ ] **Step 3: Fix root alias**

Keep root `profileRuntimeVmComparison` depending on the restored module task.

- [ ] **Step 4: Update profiling docs**

Document stable TSV, timestamped archives, per-run Markdown, and all-runs comparison.

### Task 5: Verification and Commit

- [ ] **Step 1: Run targeted tests**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests '*RuntimeVmProfilingProfileCodecTest' --tests '*RuntimeVmProfilingReportFormatterTest' --tests '*RuntimeVmProfilingReportAggregationTest' --rerun-tasks
```

- [ ] **Step 2: Run profiling task**

Run:

```bash
./gradlew profileRuntimeVmComparison
```

Expected: stable TSV, timestamped run directory, per-run Markdown, and historical comparison Markdown are written.

- [ ] **Step 3: Check whitespace and commit**

Run:

```bash
git diff --check
git add build.gradle.kts modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportTest.kt modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportAggregationTest.kt docs/PROFILING.md docs/superpowers/specs/2026-05-08-runtime-vm-historical-profiling-reports-design.md docs/superpowers/plans/2026-05-08-runtime-vm-historical-profiling-reports.md
git commit -m "test: archive runtime vm profiling reports"
```
