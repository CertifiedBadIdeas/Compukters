# Standalone Playground CLI Implementation Plan

> Issue: [#506](https://github.com/CertifiedBadIdeas/Compukters/issues/506)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `./gradlew :playground:run --args examples/hello`, which securely loads a bounded Kotlin project, compiles it in the isolated K2 worker, verifies and executes the artifact through JNI/Rust, and connects terminal requests to stdin/stdout.

**Architecture:** `compiler-client` gains a strict loader for the Gradle-produced worker payload so callers do not reconstruct trusted identity data. A new lightweight `playground` application composes project loading, the forked compiler controller, `VmSession`, and the suspend capability registry; its orchestration depends on narrow interfaces so outcome and exit-code behavior is unit-testable without native state. A separate end-to-end Gradle test owns the real worker/JNI lifecycle and exact prepared terminal transcript.

**Tech Stack:** Kotlin/JVM 17, Gradle application plugin, kotlinx.coroutines, existing compiler-client worker protocol, JNI adapter, Rust Compukter VM.

---

### Task 1: Load and validate a published compiler worker payload

**Files:**
- Create: `modules/compiler-client/src/main/kotlin/ru/lazyhat/compukters/compiler/worker/controller/WorkerPayloadLoader.kt`
- Create: `modules/compiler-client/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/controller/WorkerPayloadLoaderTest.kt`

- [ ] **Step 1: Write the failing loader tests**

Cover a canonical `worker.payload` plus one JAR, strict UTF-8, missing/duplicate/unknown keys, malformed file rows and hex, escaping paths, manifest byte limit, missing files, size mismatch, and SHA-256 mismatch. The success assertion must compare the parsed `WorkerIdentity`, ordered classpath, and payload hash.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `./gradlew-sandbox :compiler-client:test --tests '*WorkerPayloadLoaderTest' --rerun-tasks --no-daemon`

Expected: compilation fails because `WorkerPayloadLoader` is unresolved.

- [ ] **Step 3: Implement the bounded loader**

Expose:

```kotlin
data class WorkerPayloadLoadLimits(
    val manifestBytes: Int = 1024 * 1024,
    val files: Int = 128,
    val payloadBytes: Long = 512L * 1024 * 1024,
)

object WorkerPayloadLoader {
    fun load(root: Path, limits: WorkerPayloadLoadLimits = WorkerPayloadLoadLimits()): PublishedWorkerPayload
}
```

Read the manifest as bounded bytes before strict UTF-8 decoding. Require one each of `format`, `compiler`, `language`, `codegenAbi`, `artifactWriter`, `mainClass`, and `payloadSha256`; require `format=1`; parse canonical sorted `file=<path>\t<size>\t<sha256>` records; reject unknown or duplicate scalar keys. Validate normalized relative paths, file count and checked total size, regular files without symlinks, exact sizes, and hashes before returning `PublishedWorkerPayload`.

- [ ] **Step 4: Run focused and module checks**

Run:

```bash
./gradlew-sandbox :compiler-client:test --tests '*WorkerPayloadLoaderTest' --rerun-tasks --no-daemon
./gradlew-sandbox :compiler-client:check --rerun-tasks --no-daemon
```

Expected: both commands succeed.

- [ ] **Step 5: Commit**

```bash
git add modules/compiler-client
git commit -m "feat(compiler): load published worker payloads (#506)"
```

### Task 2: Add testable playground orchestration and CLI diagnostics

**Files:**
- Modify: `settings.gradle.kts`
- Create: `modules/playground/build.gradle.kts`
- Create: `modules/playground/src/main/kotlin/ru/lazyhat/compukters/playground/PlaygroundOptions.kt`
- Create: `modules/playground/src/main/kotlin/ru/lazyhat/compukters/playground/PlaygroundCompiler.kt`
- Create: `modules/playground/src/main/kotlin/ru/lazyhat/compukters/playground/PlaygroundExecutor.kt`
- Create: `modules/playground/src/main/kotlin/ru/lazyhat/compukters/playground/PlaygroundApplication.kt`
- Create: `modules/playground/src/main/kotlin/ru/lazyhat/compukters/playground/PlaygroundMain.kt`
- Create: `modules/playground/src/test/kotlin/ru/lazyhat/compukters/playground/PlaygroundOptionsTest.kt`
- Create: `modules/playground/src/test/kotlin/ru/lazyhat/compukters/playground/PlaygroundApplicationTest.kt`

- [ ] **Step 1: Write failing option and orchestration tests**

Assert one canonical project path, optional `--emit <path>`, optional `--debug`, rejection of missing/duplicate/unknown arguments, exact diagnostic rendering with virtual path and UTF-16 span, artifact emission only after compile success, and stable exit categories for input, compiler, worker platform, verification/admission/start, trap, VM fault, host failure/EOF, quota, and launcher faults. Fakes must prove compiler and VM resources close on every path.

- [ ] **Step 2: Run tests and verify RED**

Run: `./gradlew-sandbox :playground:test --rerun-tasks --no-daemon`

Expected: compilation fails because the playground types are unresolved.

- [ ] **Step 3: Implement the composition boundaries**

Use these core interfaces and result model:

```kotlin
fun interface PlaygroundCompiler {
    fun compile(project: Path): CompileResult
}

fun interface PlaygroundExecutor {
    suspend fun execute(artifact: ByteArray): PlaygroundExecution
}

sealed interface PlaygroundExecution {
    data object Success : PlaygroundExecution
    data class Trap(val trap: GuestTrap) : PlaygroundExecution
    data class Fault(val fault: VmFault) : PlaygroundExecution
    data class HostFailure(val kind: HostFailureKind, val code: Long) : PlaygroundExecution
    data class Quota(val kind: QuotaKind, val limit: Long, val consumed: Long) : PlaygroundExecution
    data class PlatformFailure(val detail: String) : PlaygroundExecution
}
```

The production compiler loads `ProjectSnapshot`, loads the published payload, launches only the configured Java executable with fixed worker limits, and waits under the controller timeout. The executor loads the configured JNI library, advances fixed guest/maintenance slices with a fixed total slice bound, dispatches terminal requests only after JNI returns, resumes typed responses, and maps every terminal VM outcome. `PlaygroundApplication.run` writes ordinary guest output only to stdout, diagnostics only to stderr, hides stack traces unless `--debug` is present, and returns documented stable exit codes; `main` is the only place that calls `exitProcess`.

- [ ] **Step 4: Configure the application task**

`modules/playground/build.gradle.kts` must depend only on `compiler-client`, `native-runtime`, stdlib, and coroutines. Its `run` task depends on `:compiler-k2:prepareCompilerWorkerPayload` and root `cargoBuildCompukterJni`, passes absolute worker/JNI paths as system properties, and forwards `System.in` with `standardInput = System.`in``. The production runtime classpath must not contain Kotlin compiler implementation JARs.

- [ ] **Step 5: Run focused checks**

Run:

```bash
./gradlew-sandbox :playground:test --rerun-tasks --no-daemon
./gradlew-sandbox :playground:check --rerun-tasks --no-daemon
```

Expected: tests and lint succeed.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts modules/playground
git commit -m "feat(playground): compose compiler and VM execution (#506)"
```

### Task 3: Add the real hello project and end-to-end gate

**Files:**
- Create: `examples/hello/greeting.kt`
- Create: `examples/hello/main.kt`
- Create: `modules/playground/src/test/kotlin/ru/lazyhat/compukters/playground/integration/PlaygroundEndToEndTest.kt`
- Modify: `modules/playground/build.gradle.kts`
- Modify: `build.gradle.kts`
- Modify: `README.md`

- [ ] **Step 1: Add the failing real-process test**

Run `PlaygroundApplication` against `examples/hello` with prepared UTF-8 stdin `Ada\r\n`. Assert exit code zero, empty stderr, exact stdout `Your name: Hello, Ada!\n`, and optional emitted bytes beginning with `CPKT`. Add a second real compile case with invalid Kotlin and assert a source-positioned diagnostic and no VM start.

- [ ] **Step 2: Run the dedicated test and verify RED**

Run: `./gradlew-sandbox :playground:endToEndTest --rerun-tasks --no-daemon`

Expected: failure because the example and/or end-to-end task is not yet complete.

- [ ] **Step 3: Add the example and integration task**

Use:

```kotlin
// examples/hello/greeting.kt
fun greeting(name: String): String = "Hello, " + name + "!"

// examples/hello/main.kt
suspend fun main() {
    print("Your name: ")
    val name = readln()
    println(greeting(name))
}
```

Configure `endToEndTest` to depend on the worker payload and release JNI library, exclude its package from ordinary unit tests, inject absolute paths, and make `:playground:check` plus root `verifyLocalFull` depend on it.

- [ ] **Step 4: Document and manually exercise the command**

Document:

```bash
./gradlew :playground:run --args examples/hello
./gradlew :playground:run --args="examples/hello --emit build/hello.cpkt"
```

Run the first command with prepared stdin and verify the exact transcript without a stack trace.

- [ ] **Step 5: Run final verification**

Run:

```bash
./gradlew-sandbox :compiler-client:check :playground:check --rerun-tasks --no-daemon
git diff --check
git status --short
```

Expected: all checks succeed and only the intended Task 3 files are modified.

- [ ] **Step 6: Commit**

```bash
git add examples/hello modules/playground build.gradle.kts README.md
git commit -m "feat(playground): run Kotlin projects end to end (#506)"
```

## Self-Review

- Spec coverage: bounded project input, isolated compiler, artifact verification/admission, JNI execution, suspend terminal dispatch, UTF-8/UTF-16 conversion, structured failures, prepared stdin/exact stdout, optional artifact emission, resource cleanup, and local verification are assigned above.
- Intentional later work: in-game syntax highlighting belongs to the future Minecraft IDE; this standalone CLI consumes ordinary `.kt` files and prints source-positioned diagnostics.
- Placeholder scan: no deferred implementation choices remain in this slice.
- Type consistency: all tasks use existing `CompileResult`, `VmOutcome`, `TerminalCapability`, and the new production payload loader; the end-to-end task uses the same application entry path as `run`.
- Execution consistency: the user selected inline execution without subagents or a worktree, so implementation proceeds in the current branch immediately after this plan.
