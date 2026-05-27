# Runtime VM Profiling Report Task Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a single Gradle task that runs JVM and Rust VM profiling workloads and writes a Markdown comparison report.

**Architecture:** Extract the current profiling workload setup into a reusable test helper, add a report formatter and a report-only JUnit test, then register `profileRuntimeVmComparison` Gradle tasks. The report test switches `ckl.vm.runner` between Kotlin and Rust in the same JVM, runs the same workloads, and writes `build/reports/profiling/runtime-vm-comparison.md`.

**Tech Stack:** Kotlin/JVM, JUnit 5, Gradle Kotlin DSL, existing runtime/display/compiler profiling collectors, Rust JNI library built by `buildRustVmNativeLibrary`.

---

## File Structure

- Modify `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeDisplayProfilingTest.kt`
  - Keep only assertions; delegate workload execution to a helper.
- Create `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeProfilingWorkload.kt`
  - Owns shared workload setup, tick loop, terminal workload, held-Enter workload, and runner-property helper.
- Create `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt`
  - Owns report data classes and Markdown formatting.
- Create `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportTest.kt`
  - Runs JVM/Rust workloads and writes the report.
- Create `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt`
  - Unit-tests the Markdown formatter.
- Modify `modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts`
  - Registers module-level `profileRuntimeVmComparison`.
- Modify `build.gradle.kts`
  - Registers root alias `profileRuntimeVmComparison` so `./gradlew profileRuntimeVmComparison` works.
- Modify `docs/PROFILING.md`
  - Documents the one-task workflow and report location.

## Task 1: Add report formatter test first

**Files:**
- Create: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt`
- Create later: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt`

- [ ] **Step 1: Write failing formatter test**

Create a test that builds minimal fake snapshots and expects Markdown sections, ratios, host-call rows, and the Rust instruction-metrics note.

- [ ] **Step 2: Run RED**

Run: `./gradlew :v1_21_1-neoforge:test --tests '*RuntimeVmProfilingReportFormatterTest' --rerun-tasks`

Expected: FAIL because `RuntimeVmProfilingReport`, `VmRunnerProfile`, or `RuntimeVmProfilingReportFormatter` does not exist.

- [ ] **Step 3: Implement minimal formatter**

Create `RuntimeVmProfilingReport.kt` with:

- `data class VmRunnerProfile(val runnerName: String, val workloads: List<RuntimeWorkloadProfile>)`
- `data class RuntimeWorkloadProfile(...)`
- `object RuntimeVmProfilingReportFormatter { fun markdown(jvm: VmRunnerProfile, rust: VmRunnerProfile): String }`

The formatter must include:

- title `# Runtime VM Profiling Comparison`;
- per-workload tables for display/runtime/compiler metrics;
- host-call comparison table;
- ratio values such as `1.50x`;
- `Rust instruction metrics are currently unavailable` when Rust instructions are empty.

- [ ] **Step 4: Run GREEN**

Run: `./gradlew :v1_21_1-neoforge:test --tests '*RuntimeVmProfilingReportFormatterTest' --rerun-tasks`

Expected: PASS.

## Task 2: Extract reusable workload helper

**Files:**
- Create: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeProfilingWorkload.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeDisplayProfilingTest.kt`

- [ ] **Step 1: Move shared private workload code**

Move the following from `RuntimeDisplayProfilingTest` into `RuntimeProfilingWorkload`:

- `ProfilingRun`
- `TickObservation`
- `HeldEnterProfilingRun`
- `ClasspathFirmwareLoader`
- `profile()`
- `runTicks(...)`
- `waitForBootCompile(...)`
- `waitForRuntimeProgress(...)`
- `runTerminalWorkload(...)`
- `runHeldEnterWorkload(...)`

Expose `runTerminalWorkload(...)` and `runHeldEnterWorkload(...)` as functions on `object RuntimeProfilingWorkload`.

- [ ] **Step 2: Update existing profiling test**

Replace calls in `RuntimeDisplayProfilingTest`:

- `runTerminalWorkload(...)` → `RuntimeProfilingWorkload.runTerminalWorkload(...)`
- `runHeldEnterWorkload(...)` → `RuntimeProfilingWorkload.runHeldEnterWorkload(...)`

- [ ] **Step 3: Verify existing JVM profiling tests still pass**

Run: `./gradlew :v1_21_1-neoforge:test --tests 'ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeDisplayProfilingTest' --rerun-tasks`

Expected: PASS.

## Task 3: Add report-generation test

**Files:**
- Create: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeProfilingWorkload.kt`

- [ ] **Step 1: Add runner-property helper**

Add `RuntimeProfilingWorkload.withVmRunner(runner: String, nativeLibrary: String? = null, block: () -> T): T` that saves/restores `ckl.vm.runner` and `ckl.vm.native.library`.

- [ ] **Step 2: Write report-generation test**

Create `RuntimeVmProfilingReportTest.generatesRuntimeVmComparisonReport()`:

- read `ckl.vm.native.library` and fail clearly if blank;
- run `sustainedTerminalWorkloadProducesNoDelayProfilingMetrics`, `bundledTerminalWorkloadProducesProfilingMetrics`, and `heldEnterWorkloadProducesBacklogProfilingMetrics` under `kotlin`;
- run the same workloads under `rust`;
- write Markdown to `System.getProperty("ckl.profiling.report.path", "build/reports/profiling/runtime-vm-comparison.md")`;
- assert the file exists and contains both `JVM` and `Rust`.

- [ ] **Step 3: Run report test manually with existing Rust library path**

Run: `./gradlew buildRustVmNativeLibrary :v1_21_1-neoforge:test --tests '*RuntimeVmProfilingReportTest' -Dckl.vm.native.library=$PWD/native/ckl-vm/target/debug/libckl_vm.so --rerun-tasks`

Expected: PASS and report file exists.

## Task 4: Add one Gradle task

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/build.gradle.kts`
- Modify: `build.gradle.kts`

- [ ] **Step 1: Register module-level Test task**

Add `profileRuntimeVmComparison` in the NeoForge build script:

- type `Test`;
- group `verification`;
- depends on `buildRustVmNativeLibrary`;
- includes only `RuntimeVmProfilingReportTest`;
- passes `ckl.vm.native.library` and `ckl.profiling.report.path`;
- enables standard streams.

- [ ] **Step 2: Register root alias task**

Add root `profileRuntimeVmComparison` depending on `:v1_21_1-neoforge:profileRuntimeVmComparison`.

- [ ] **Step 3: Run task**

Run: `./gradlew profileRuntimeVmComparison --rerun-tasks`

Expected: PASS and print/write `modules/v1_21_1/v1_21_1-neoforge/build/reports/profiling/runtime-vm-comparison.md`.

## Task 5: Document and verify

**Files:**
- Modify: `docs/PROFILING.md`

- [ ] **Step 1: Add documentation**

Document:

```bash
./gradlew profileRuntimeVmComparison
```

and the Markdown report location.

- [ ] **Step 2: Run final checks**

Run:

```bash
./gradlew profileRuntimeVmComparison --rerun-tasks
./gradlew :v1_21_1-neoforge:test --tests 'ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeDisplayProfilingTest' --rerun-tasks
git diff --check
```

Expected: all commands pass; `git diff --check` has no output.

## Self-Review

- Spec coverage: the plan builds Rust library, runs JVM/Rust workloads, writes Markdown, keeps normal tests unchanged, and documents the task.
- Placeholder scan: no `TBD`, `TODO`, or ambiguous follow-up tasks remain.
- Type consistency: report model names are introduced before use and stay consistent.
- Execution consistency: the plan uses existing Gradle/JUnit mechanisms and explicit Rust native library properties.
