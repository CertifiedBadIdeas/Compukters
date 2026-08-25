# Guest Process and Standard I/O Implementation Plan

> Issue: [#530](https://github.com/CertifiedBadIdeas/Compukters/issues/530)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan inline, task-by-task, as requested for this repository. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the transitional raw command-line/capability-mask process API with bounded structured arguments, typed completion, explicit exit, task-local VM blocking, inherited standard streams, and a POSIX-lite shell.

**Architecture:** Keep Rust's single foreground process stack and machine-owned terminal/VFS, but make every wait and completion belong to an explicit execution-task identity. K2 lowers trusted synchronous-looking SDK calls to asynchronous VM operations, while ordinary Kotlin classes and arrays represent the public API. `compukter/process@2` and `compukter/stdio@1` replace `process@1` in one coordinated ABI migration; no compatibility adapter remains.

**Tech Stack:** Kotlin 2.4 K2 IR, Compukter Artifact/VM, Rust 2021, JDK 25 FFM, Gradle 9.7, NeoForge 26.1 GameTest.

---

## File Structure

- `modules/compiler-artifact/.../model/{Artifact.kt,Types.kt}` and `.../write/*` — encode the entry argument contract and the one required artifact/VM ABI bump.
- `modules/compiler-k2/.../{MinimalScriptLowering.kt,KotlinProjectLowering.kt,GuestTypeRegistry.kt}` — discover the four legal `main` forms and lower `Array<String>`, SDK result classes, fields, constructors, and type tests.
- `modules/compiler-k2/.../TrustedIntrinsicRegistry.kt` — distinguish a Kotlin `suspend` declaration from a VM-blocking trusted operation and publish process/stdio v2 identities.
- `modules/guest-api-core/src/main/kotlin/compukter/{process,io}` — public typed process and standard-I/O wrappers plus private trusted bindings.
- `host/compukter-vm/src/execution/{host.rs,session.rs,machine.rs}` — bounded entry object materialization and task-owned host waits.
- `host/compukter-vm/src/{process.rs,computer.rs,stdio.rs}` and `host/compukter-vm/src/terminal/*` — process-v2 status/diagnostics/exit, machine-owned streams, canonical input, and cleanup.
- `system/programs/{boot.kt,shell.kt,kotlinc.kt,edit.kt}` — structured argv consumers and the bounded shell lexer.
- `modules/core/.../ProgramRuntimeHostIntegrationTest.kt` and `modules/v26_1/.../ComputerBlockGameTest.kt` — full boot/shell/compile/run verification.

## Milestone 1 — Structured Entry Arguments ([#531](https://github.com/CertifiedBadIdeas/Compukters/issues/531))

### Task 1: Declare the internal entry argument shape

**Files:**
- Modify: `modules/compiler-artifact/src/main/kotlin/ru/lazyhat/compukters/compiler/artifact/model/Artifact.kt`
- Modify: `modules/compiler-artifact/src/main/kotlin/ru/lazyhat/compukters/compiler/artifact/write/ArtifactWriter.kt`
- Modify: `modules/compiler-artifact/src/main/kotlin/ru/lazyhat/compukters/compiler/artifact/write/ArtifactValidator.kt`
- Test: `modules/compiler-artifact/src/test/kotlin/ru/lazyhat/compukters/compiler/artifact/write/ArtifactValidatorTest.kt`
- Test: `modules/compiler-artifact/src/test/kotlin/ru/lazyhat/compukters/compiler/artifact/write/MinimalArtifactGoldenTest.kt`

- [ ] **Step 1: Write failing artifact tests**

Add a parameter-kind field to the entry record and test both legal values and a mismatch with the referenced function:

```kotlin
enum class EntryArguments { NONE, STRING_ARRAY }

val withArgs = minimalArtifact().copy(
    entry = EntryPoint(ModuleId.of(0u), FunctionId.of(0u), EntryArguments.STRING_ARRAY),
)
assertEquals(ArtifactWriteResult.Success::class, ArtifactWriter.write(withArgs, limits)::class)
assertFailureCode(
    withArgs.copy(entry = withArgs.entry.copy(arguments = EntryArguments.NONE)),
    "ENTRY_SIGNATURE_MISMATCH",
)
```

- [ ] **Step 2: Run the focused tests and verify RED**

```bash
./gradlew-sandbox :compiler-artifact:test --tests '*ArtifactValidatorTest*entry*' --tests '*MinimalArtifactGoldenTest*'
```

Expected: compilation fails because `EntryArguments` and the third `EntryPoint` property do not exist.

- [ ] **Step 3: Add the model, encoding, and validation**

Use an explicit byte rather than inferring from the function at runtime:

```kotlin
enum class EntryArguments(val artifactTag: UInt) {
    NONE(0u),
    STRING_ARRAY(1u),
}

data class EntryPoint(
    val module: ModuleId,
    val function: FunctionId,
    val arguments: EntryArguments = EntryArguments.NONE,
)
```

Write the tag in the first formerly-reserved header byte immediately after the entry function ID, require the other fifteen bytes to remain zero, and bump the writer format major from `1` to `2` in this same change. Validation must require zero parameters for `NONE`, and one non-null reference to a nominal `Array` whose element is a non-null `kotlin.String` reference for `STRING_ARRAY`.

- [ ] **Step 4: Run artifact tests GREEN**

```bash
./gradlew-sandbox :compiler-artifact:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit the artifact contract**

```bash
git add modules/compiler-artifact
git commit -m "feat(artifact): declare structured entry arguments (#531)"
```

### Task 2: Materialize `Array<String>` in the child heap

**Files:**
- Modify: `host/compukter-vm/src/artifact/mod.rs`
- Modify: `host/compukter-vm/src/artifact/format.rs`
- Modify: `host/compukter-vm/src/decode/container.rs`
- Modify: `host/compukter-vm/src/decode/records.rs`
- Modify: `host/compukter-vm/src/execution/host.rs`
- Modify: `host/compukter-vm/src/execution/session.rs`
- Modify: `host/compukter-vm/src/execution/machine.rs`
- Modify: `host/compukter-vm/src/execution/fixtures.rs`
- Test: `host/compukter-vm/src/execution/session_tests.rs`

- [ ] **Step 1: Add RED tests for exact argv ownership and bounds**

Use a fixture whose `main(args)` returns `args.size`, `args[0].length`, or a selected UTF-16 code unit. Cover zero arguments, an empty string, unpaired surrogates, embedded NUL, excessive count, excessive per-value length, excessive aggregate length, and heap allocation failure before `start` becomes visible:

```rust
let arguments: &[Box<[u16]>] = &[
    "".encode_utf16().collect(),
    vec![0x0041, 0x0000, 0xD800, 0x0042].into_boxed_slice(),
];
session.start(&[EntryValue::StringArray(arguments)]).unwrap();
assert_halts_with_i32(&mut session, 4);
assert_eq!(RunError::EntryArgumentLimit(EntryArgumentLimit::Count), too_many);
```

- [ ] **Step 2: Run and verify RED**

```bash
CARGO_TARGET_DIR=.toolchain/build/cargo/compukter-vm cargo test --manifest-path host/compukter-vm/Cargo.toml --locked --offline entry_string_array -- --nocapture
```

Expected: compilation fails because `EntryValue::StringArray`, entry limits, and entry object allocation do not exist.

- [ ] **Step 3: Add bounded borrowed host input**

Do not retain caller slices in `Session`:

```rust
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum EntryValue<'a> {
    I32(i32),
    I64(i64),
    F32(u32),
    F64(u64),
    Bool(bool),
    Char(u16),
    StringArray(&'a [Box<[u16]>]),
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct EntryArgumentLimits {
    pub maximum_count: u32,
    pub maximum_code_units_per_argument: u32,
    pub maximum_total_code_units: u32,
}
```

Place the three values in `ExecutionProfile`. Check count and UTF-16 lengths with `checked_add` before mutating the heap.

- [ ] **Step 4: Add one transactional machine allocator**

Implement:

```rust
fn materialize_entry_string_array(
    &mut self,
    arguments: &[Box<[u16]>],
    limits: EntryArgumentLimits,
) -> Result<EntryArgument, RunError>
```

Resolve the verified entry's exact array/string types, allocate every string and then the reference array through the managed heap, store references with the existing array store primitives, root all intermediate values during allocation, and roll the pristine machine back on any failure. `main()` validates and owns the host payload at the `ComputerMachine` layer but calls `Session::start(&[])`; `main(args)` calls `Session::start(&[EntryValue::StringArray(args)])`.

Set `FORMAT_MAJOR` to `2`, decode the entry-argument tag from header byte `48`, reject format major `1` as `UnsupportedVersion`, and retain the invariant that the remaining header-reserved bytes are zero. This is the only artifact container version bump in the plan.

- [ ] **Step 5: Run VM tests GREEN**

```bash
CARGO_TARGET_DIR=.toolchain/build/cargo/compukter-vm cargo test --manifest-path host/compukter-vm/Cargo.toml --locked --offline entry_string_array -- --nocapture
cargo fmt --manifest-path host/compukter-vm/Cargo.toml --check
```

Expected: all selected tests pass and formatting is clean.

- [ ] **Step 6: Commit in the VM submodule and record its pointer**

```bash
git -C host/compukter-vm add src
git -C host/compukter-vm commit -m "feat(execution): materialize bounded entry argv (#531)"
git add host/compukter-vm
git commit -m "chore(vm): adopt structured entry argv (#531)"
```

### Task 3: Lower `Array<String>` and all four `main` forms

**Files:**
- Create: `modules/compiler-k2/src/main/kotlin/ru/lazyhat/compukters/compiler/worker/k2/GuestTypeRegistry.kt`
- Modify: `modules/compiler-k2/src/main/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLowering.kt`
- Modify: `modules/compiler-k2/src/main/kotlin/ru/lazyhat/compukters/compiler/worker/k2/KotlinProjectLowering.kt`
- Test: `modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt`

- [ ] **Step 1: Add entry discovery tests**

Compile each legal declaration twice and compare bytes:

```kotlin
listOf(
    "fun main() {}",
    "fun main(args: Array<String>) { if (args.size < 0) return }",
    "suspend fun main() {}",
    "suspend fun main(args: Array<String>) { if (args.size < 0) return }",
).forEach { source -> assertDeterministicSuccess(source) }
```

Also assert `INVALID_ENTRY_POINT` for zero `main`s, `main(IntArray)`, non-`Unit` return, two no-arg mains, and one `main()` plus one `main(Array<String>)` across separate files.

- [ ] **Step 2: Add array-lowering RED tests**

```kotlin
fun main(args: Array<String>) {
    var index = 0
    while (index < args.size) {
        val value = args[index]
        index = index + value.length
    }
}
```

Assert that the artifact contains a nominal `Array` of non-null string refs plus `ArrayLength` and `ArrayLoad`.

- [ ] **Step 3: Run and verify RED**

```bash
./gradlew-sandbox :compiler-k2:test --tests '*MinimalScriptLoweringTest*main*' --tests '*MinimalScriptLoweringTest*string array*'
```

Expected: the parameterized forms report `INVALID_ENTRY_POINT`, and generic-array access reports `UNSUPPORTED_IR`.

- [ ] **Step 4: Centralize exact guest types**

Create `GuestTypeRegistry` with stable symbol-to-artifact mappings for `kotlin.String`, `kotlin.CharArray`, and `kotlin.Array<String>`:

```kotlin
internal data class GuestTypeRegistry(
    val string: IrClassSymbol,
    val charArray: IrClassSymbol,
    val stringArray: IrClassSymbol,
) {
    fun valueType(type: IrType): ValueType = when {
        type.isString() -> stringRef
        type.isCharArray() -> charArrayRef
        type.isArrayOfString() -> stringArrayRef
        else -> scalarValueType(type)
    }
}
```

Reject nullable elements and every generic array other than exact `Array<String>`.

- [ ] **Step 5: Implement exact entry selection and array operations**

Select exactly one function whose return is `Unit` and whose parameters are either empty or one exact `Array<String>`. Set `EntryPoint.arguments` from the selected form. Generalize the existing hard-coded `CharArray` length/load/store paths to use the registry while retaining `CharArray(size)` as the only public array constructor for now.

- [ ] **Step 6: Run compiler tests GREEN and commit**

```bash
./gradlew-sandbox :compiler-k2:test
git add modules/compiler-k2
git commit -m "feat(k2): lower Kotlin main argv (#531)"
```

The same lowering must support exact `emptyArray<String>()`, `arrayOf<String>(...)`, indexed stores during their construction, and `copyOfRange` for `Array<String>`. It must also lower an omitted default argument when the callee's default expression is one of those supported array expressions, so `Process.run(path)` can honor its public `args = emptyArray()` contract without a generated JVM `$default` helper.

### Task 4: Prove compiler-to-VM argv conformance

**Files:**
- Modify: `modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt`
- Modify: `modules/compiler-k2/build.gradle.kts`
- Modify: `build.gradle.kts`
- Modify: `modules/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs`

- [ ] **Step 1: Generate a deterministic argv artifact**

Compile this program twice and write it to a dedicated Gradle output property:

```kotlin
import compukter.terminal.Terminal

fun main(args: Array<String>) {
    Terminal.write(args.size.toString() + ":" + args[0] + ":" + args[1])
}
```

- [ ] **Step 2: Execute exact UTF-16 arguments in Rust**

Pass `arrayOf("", "A\u0000\uD800B")`, service the terminal write, and assert the exact UTF-16 payload `2::A\u0000\uD800B` before normal halt.

- [ ] **Step 3: Run and commit vertical conformance**

```bash
./gradlew-sandbox testKotlinArgvVmConformance
git add build.gradle.kts modules/compiler-k2 modules/compiler-artifact
git commit -m "test(language): prove structured argv end to end (#531)"
```

Expected: `BUILD SUCCESSFUL`.

## Milestone 2 — Synchronous-Looking VM Blocking ([#532](https://github.com/CertifiedBadIdeas/Compukters/issues/532))

### Task 5: Separate Kotlin suspension from VM blocking in K2

**Files:**
- Modify: `modules/compiler-k2/src/main/kotlin/ru/lazyhat/compukters/compiler/worker/k2/TrustedIntrinsicRegistry.kt`
- Modify: `modules/compiler-k2/src/main/kotlin/ru/lazyhat/compukters/compiler/worker/k2/KotlinProjectLowering.kt`
- Modify: `modules/guest-api-core/src/main/kotlin/compukter/terminal/Terminal.kt`
- Test: `modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/TrustedIntrinsicRegistryTest.kt`
- Test: `modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt`

- [ ] **Step 1: Add RED identity tests**

Require a non-suspending trusted SDK declaration to resolve as a VM-blocking operation, while the same user declaration does not:

```kotlin
val trusted = callable(
    bundle = TERMINAL_BUNDLE_ID,
    name = "compukter.terminal.Terminal.awaitEvent",
    suspending = false,
    parameters = emptyList(),
    result = INT,
)
assertEquals(BlockingMode.VM_TASK, requireOperation(trusted).blocking)
assertNull(TrustedIntrinsicRegistry.resolve(trusted.copy(bundleIdentity = null)))
```

- [ ] **Step 2: Run and verify RED**

```bash
./gradlew-sandbox :compiler-k2:test --tests '*TrustedIntrinsicRegistryTest*blocking*'
```

Expected: `BlockingMode` does not exist and the current registry requires `suspending == true`.

- [ ] **Step 3: Model lowering behavior explicitly**

```kotlin
internal enum class BlockingMode { NONE, VM_TASK }

internal data class CapabilityOperation(
    val capability: TrustedCapabilityIdentity,
    val operation: UInt,
    val blocking: BlockingMode,
)
```

`blocking == VM_TASK` always emits `Instruction.CapabilityCallAsync` with a continuation block, regardless of `IrSimpleFunction.isSuspend`. `BlockingMode.NONE` emits `CapabilityCallSync`. The trusted identity still includes `suspending`, so the registry accepts only the exact SDK declaration and cannot be spoofed by source code.

- [ ] **Step 4: Make raw terminal wait ordinary Kotlin**

```kotlin
fun awaitEvent(): Int = 0
```

Compile `fun main() { val event = Terminal.awaitEvent(); Terminal.write(event.toString()) }` and assert `CapabilityCallAsync` plus a resume block in a non-suspending function.

- [ ] **Step 5: Run and commit**

```bash
./gradlew-sandbox :compiler-k2:test
git add modules/compiler-k2 modules/guest-api-core
git commit -m "feat(k2): lower trusted VM-blocking calls (#532)"
```

### Task 6: Attribute pending host waits to an execution task

**Files:**
- Modify: `host/compukter-vm/src/execution/host.rs`
- Modify: `host/compukter-vm/src/execution/session.rs`
- Modify: `host/compukter-vm/src/execution/machine.rs`
- Test: `host/compukter-vm/src/execution/session_tests.rs`

- [ ] **Step 1: Add RED ownership tests**

Assert that a published request reports task ID `1`, the response resumes that same task, a duplicate/wrong task response is rejected, and terminal teardown clears ownership:

```rust
assert_eq!(TaskId::ROOT, request.task_id());
assert_eq!(Err(ResumeError::WrongTask), session.resume_for(other, id, response));
session.resume_for(TaskId::ROOT, id, response).unwrap();
assert_eq!(None, session.pending_task_for_test());
```

- [ ] **Step 2: Run and verify RED**

```bash
CARGO_TARGET_DIR=.toolchain/build/cargo/compukter-vm cargo test --manifest-path host/compukter-vm/Cargo.toml --locked --offline task_owned_request -- --nocapture
```

Expected: `TaskId`, task-aware request views, and `resume_for` do not exist.

- [ ] **Step 3: Introduce the minimal task identity without a scheduler**

```rust
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct TaskId(u32);

impl TaskId {
    pub const ROOT: Self = Self(1);
}

struct PendingRequest {
    id: RequestId,
    task: TaskId,
    capability: u32,
    operation: u32,
}
```

The current machine owns only `TaskId::ROOT`. Thread this identity through request preparation, publication, response, pending string materialization, and capability continuation. Do not implement a runnable queue or multiple tasks in this issue.

- [ ] **Step 4: Run VM tests and commit**

```bash
CARGO_TARGET_DIR=.toolchain/build/cargo/compukter-vm cargo test --manifest-path host/compukter-vm/Cargo.toml --locked --offline
cargo fmt --manifest-path host/compukter-vm/Cargo.toml --check
git -C host/compukter-vm add src
git -C host/compukter-vm commit -m "refactor(execution): own host waits by task (#532)"
git add host/compukter-vm
git commit -m "chore(vm): adopt task-owned host waits (#532)"
```

## Milestone 3 — Typed Process Completion ([#533](https://github.com/CertifiedBadIdeas/Compukters/issues/533))

### Task 7: Lower the ordinary guest object subset required by `ProcessResult`

**Files:**
- Modify: `modules/compiler-k2/src/main/kotlin/ru/lazyhat/compukters/compiler/worker/k2/GuestTypeRegistry.kt`
- Modify: `modules/compiler-k2/src/main/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLowering.kt`
- Modify: `modules/compiler-k2/src/main/kotlin/ru/lazyhat/compukters/compiler/worker/k2/KotlinProjectLowering.kt`
- Test: `modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt`

- [ ] **Step 1: Add RED tests for SDK-shaped values**

Compile and inspect artifacts for this complete minimal subset:

```kotlin
sealed interface Result
data class Exited(val code: Int) : Result
data class Failed(val reason: Reason, val diagnostic: String) : Result
enum class Reason { NOT_FOUND, TRAPPED }

fun classify(value: Result): Int = when (value) {
    is Exited -> value.code
    is Failed -> if (value.reason == Reason.NOT_FOUND) value.diagnostic.length else -1
}
fun main() { classify(Exited(7)) }
```

Assert class/interface nominal types, instance fields, constructor `NewObject`/`FieldSet`, property `FieldGet`, enum singleton identity, and `IsType` branches.

- [ ] **Step 2: Run and verify RED**

```bash
./gradlew-sandbox :compiler-k2:test --tests '*MinimalScriptLoweringTest*guest object*'
```

Expected: `UNSUPPORTED_IR` at the first constructor or field access.

- [ ] **Step 3: Collect and lay out supported declarations deterministically**

Support source/trusted-SDK declarations meeting all these exact rules: non-generic class/interface; primary constructor only; immutable constructor properties of `Int`, `Boolean`, `Char`, `String`, enum, or supported reference type; no custom initializer, delegation, secondary constructor, override body, or reflection. Sort declarations by stable fully-qualified name, then assign `TypeId`, `FieldId`, and constructor `FunctionId` before body lowering.

- [ ] **Step 4: Lower constructors, property reads, enum entries, and `is`**

Constructor shape:

```text
new_object result, Exited
field_set result, Exited.code, codeArgument
return result
```

Emit enum entries as immutable static roots initialized once before entry; compare them by reference identity. Emit `is` as `Instruction.IsType`, and permit the K2 smart cast only inside the proven branch. Reject arbitrary casts and mutable/static user fields.

- [ ] **Step 5: Run compiler tests GREEN and commit**

```bash
./gradlew-sandbox :compiler-k2:test
git add modules/compiler-k2
git commit -m "feat(k2): lower typed guest result values (#533)"
```

### Task 8: Replace capability masks and raw command lines with process v2

**Files:**
- Modify: `host/compukter-vm/src/process.rs`
- Modify: `host/compukter-vm/src/computer.rs`
- Modify: `host/compukter-vm/src/lib.rs`
- Modify: `host/compukter-vm/tests/process_contract.rs`
- Modify: `host/compukter-ffi/src/bridge.rs`
- Test: `host/compukter-vm/src/computer.rs`

- [ ] **Step 1: Write the RED process-v2 matrix**

Table-test normal return, all exit codes `0..=255`, invalid exit values, every public failure category, scalar-safe bounded diagnostics, exact task ownership, diagnostic consume-once behavior, and removal of child capability delegation:

```rust
assert_eq!(ProcessCompletion::Exited(37), run_exit_fixture(37));
assert_failure(run_exit_fixture(256), ProcessFailureReason::Trapped, "exit code");
assert_failure(run_missing("/home/nope"), ProcessFailureReason::NotFound, "/home/nope");
assert_eq!(None, computer.take_process_diagnostic(TaskId::ROOT));
assert!(child_with_machine_filesystem_import().starts());
```

For every pre-publication failure, also assert the process depth, parent pending request, aggregate heap/frame reservations, input ownership, and start counter remain exactly at their pre-call values. For every child terminal path, assert the frame and all runtime-owned wait/stream state are released before the parent task is resumed.

- [ ] **Step 2: Run and verify RED**

```bash
CARGO_TARGET_DIR=.toolchain/build/cargo/compukter-vm cargo test --manifest-path host/compukter-vm/Cargo.toml --locked --offline process_v2 -- --nocapture
```

Expected: old `ProcessCapabilityMask`, positive failure codes, and command-line operations fail the new assertions.

- [ ] **Step 3: Define stable internal completion and configurable limits**

```rust
pub enum ProcessCompletion {
    Exited(u8),
    Failed { reason: ProcessFailureReason, diagnostic: Box<[u16]> },
}

#[repr(i32)]
pub enum ProcessFailureReason {
    InvalidPath = 1,
    NotFound = 2,
    AccessDenied = 3,
    NotExecutable = 4,
    InvalidProgram = 5,
    Incompatible = 6,
    LimitExceeded = 7,
    Trapped = 8,
    VmFault = 9,
    HostFailure = 10,
    IoFailure = 11,
}
```

Extend `ProcessLimits` with argv count/per-argument/total UTF-16 and diagnostic UTF-16 bounds. Tests construct small explicit limits; production values remain supplied by the machine profile rather than encoded in the ABI.

- [ ] **Step 4: Publish the exact private process@2 operations**

Use length-delimited arrays inside `ComputerMachine`, not a separator string:

```text
op 0 async run(path: String, encodedArgs: String) -> Int
op 1 sync  takeFailureDiagnostic() -> String
op 2 sync  exit(code: Int) -> Unit   // terminal; never returns on success
```

The trusted K2 wrapper owns the length-prefixed encoding/decoding contract; embedded NUL remains data. Raw `run` returns `0..255` for exit and `-reasonCode` for failure. `takeFailureDiagnostic` reads and clears only the completion stored for the calling `TaskId`. `exit` terminates the active frame directly and traps codes outside `0..255`.

Add `TrustedValueType.NOTHING`: K2 accepts the exact private `exit(Int): Nothing` declaration while the host schema remains `Unit`, and lowering treats the capability instruction as a terminating instruction with no reachable continuation.

- [ ] **Step 5: Remove production capability masks**

Delete `ProcessCapabilityMask`, capability arguments, `command_line`, `CommandLine`, and mask-based binding filtering. Admit every child against the machine's actual built-in/addon bindings; unresolved artifact imports produce `INCOMPATIBLE`. Keep verifier, VFS rights, ROM immutability, and host operation bounds unchanged.

- [ ] **Step 6: Run VM and FFI tests GREEN and commit**

```bash
CARGO_TARGET_DIR=.toolchain/build/cargo/compukter-vm cargo test --manifest-path host/compukter-vm/Cargo.toml --locked --offline
CARGO_TARGET_DIR=.toolchain/build/cargo/compukter-ffi cargo test --manifest-path host/compukter-ffi/Cargo.toml --locked --offline
cargo fmt --manifest-path host/compukter-vm/Cargo.toml --check
git -C host/compukter-vm add src tests
git -C host/compukter-vm commit -m "feat(process): implement process v2 completion (#533)"
git add host/compukter-vm host/compukter-ffi
git commit -m "feat(native): adopt process v2 completion (#533)"
```

### Task 9: Publish the typed Guest Kotlin process API

**Files:**
- Modify: `modules/guest-api-core/src/main/kotlin/compukter/process/Process.kt`
- Modify: `modules/compiler-k2/src/main/kotlin/ru/lazyhat/compukters/compiler/worker/k2/TrustedIntrinsicRegistry.kt`
- Test: `modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/TrustedIntrinsicRegistryTest.kt`
- Test: `modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt`

- [ ] **Step 1: Add RED public API compilation tests**

```kotlin
fun main() {
    when (val result = Process.run("/rom/tool", arrayOf("a", ""))) {
        is ProcessResult.Exited -> if (result.code != 0) Process.exit(result.code)
        is ProcessResult.Failed -> Process.exit(1)
    }
}
```

Assert no public overload contains a capability mask or raw command line and none of `run`, `exit`, or `Terminal.awaitEvent` is declared `suspend`.

- [ ] **Step 2: Run and verify RED**

```bash
./gradlew-sandbox :compiler-k2:test --tests '*TrustedIntrinsicRegistryTest*process v2*' --tests '*MinimalScriptLoweringTest*typed process*'
```

Expected: the new types and signatures are unresolved.

- [ ] **Step 3: Replace the SDK source**

```kotlin
package compukter.process

object Process {
    fun run(path: String, args: Array<String> = emptyArray()): ProcessResult {
        val raw = ProcessBindings.run(path, ProcessBindings.encodeArgs(args))
        return if (raw >= 0) ProcessResult.Exited(raw) else ProcessResult.Failed(
            ProcessFailureReason.fromCode(-raw),
            ProcessBindings.takeFailureDiagnostic(),
        )
    }

    fun exit(code: Int): Nothing = ProcessBindings.exit(code)
}
```

Declare the accepted `ProcessResult` sealed interface, its two data classes, and the eleven-value enum exactly as in the spec. `ProcessBindings` is `private` and is the only intrinsic-recognized identity; user code cannot call raw status or diagnostic operations. Implement the bounded length-prefix encoder with `CharArray`, `String(chars, start, length)`, and exact UTF-16 code units; two code units store the unsigned argument count, then each argument uses two code units for its unsigned length followed by its payload. Reject arithmetic overflow before allocating the buffer.

- [ ] **Step 4: Register process@2 and run GREEN**

Set the trusted bundle identity to `compukter.process-api@2`, the host identity to `compukter/process@2`, and resolve only `ProcessBindings.run`, `takeFailureDiagnostic`, and `exit` from that trusted bundle.

```bash
./gradlew-sandbox :guest-api-core:test :compiler-k2:test
git add modules/guest-api-core modules/compiler-k2
git commit -m "feat(guest-api): expose typed process results (#533)"
```

## Milestone 4 — Inherited Standard Streams ([#534](https://github.com/CertifiedBadIdeas/Compukters/issues/534))

### Task 10: Add machine-owned canonical standard streams

**Files:**
- Create: `host/compukter-vm/src/stdio.rs`
- Modify: `host/compukter-vm/src/lib.rs`
- Modify: `host/compukter-vm/src/terminal/mod.rs`
- Modify: `host/compukter-vm/src/terminal/input.rs`
- Modify: `host/compukter-vm/src/computer.rs`
- Test: `host/compukter-vm/src/stdio.rs`
- Test: `host/compukter-vm/src/computer.rs`

- [ ] **Step 1: Add RED line-discipline tests**

Cover text append/echo, Unicode-scalar Backspace, Enter-to-LF commit, line-without-terminator return, maximum line length, stdout/stderr acceptance order, raw/canonical conflicts, sequential switching, child inheritance, child cleanup, and reboot cleanup:

```rust
stdio.begin_read(TaskId::ROOT).unwrap();
stdio.accept_text(&[0x0041, 0xD83D, 0xDE00]).unwrap();
stdio.accept_key(KeyEvent::BACKSPACE).unwrap();
stdio.accept_key(KeyEvent::ENTER).unwrap();
assert_eq!(Some(vec![0x0041].into_boxed_slice()), stdio.take_line(TaskId::ROOT));
assert_eq!(Err(InputOwnershipError::CanonicalBusy), terminal.begin_raw_wait(other));
```

- [ ] **Step 2: Run and verify RED**

```bash
CARGO_TARGET_DIR=.toolchain/build/cargo/compukter-vm cargo test --manifest-path host/compukter-vm/Cargo.toml --locked --offline stdio -- --nocapture
```

Expected: `stdio` and canonical ownership do not exist.

- [ ] **Step 3: Implement bounded logical endpoints**

```rust
pub struct StandardStreams {
    input: CanonicalInput,
    output: TerminalOutput,
    error: TerminalOutput,
    maximum_line_code_units: usize,
    maximum_output_code_units: usize,
}

enum InputOwner {
    None,
    Raw { frame: usize, task: TaskId },
    Canonical { frame: usize, task: TaskId },
}
```

`stdout` and `stderr` are distinct endpoint methods but initially append to the same terminal grid in accepted-call order. Canonical input echoes only accepted text/edit operations. Backspace removes one valid surrogate pair or one code unit for an unpaired surrogate. Enter commits LF semantics but returns the line without LF.

- [ ] **Step 4: Integrate ownership and cleanup**

Store `StandardStreams` once on `ComputerMachine`; frames inherit references by machine identity, not guest objects. A process pop cancels only waits owned by that frame/task. Reboot constructs fresh streams and terminal state. Reject concurrent raw/canonical acquisition before consuming any event.

- [ ] **Step 5: Run and commit**

```bash
CARGO_TARGET_DIR=.toolchain/build/cargo/compukter-vm cargo test --manifest-path host/compukter-vm/Cargo.toml --locked --offline
cargo fmt --manifest-path host/compukter-vm/Cargo.toml --check
git -C host/compukter-vm add src
git -C host/compukter-vm commit -m "feat(stdio): add terminal-backed standard streams (#534)"
git add host/compukter-vm
git commit -m "chore(vm): adopt inherited standard streams (#534)"
```

### Task 11: Publish stdio@1 and ordinary Kotlin functions

**Files:**
- Create: `modules/guest-api-core/src/main/kotlin/compukter/io/Stderr.kt`
- Modify: `modules/compiler-k2/src/main/kotlin/ru/lazyhat/compukters/compiler/worker/k2/K2CompilerAdapter.kt`
- Modify: `modules/compiler-k2/src/main/kotlin/ru/lazyhat/compukters/compiler/worker/k2/TrustedIntrinsicRegistry.kt`
- Modify: `host/compukter-vm/src/computer.rs`
- Test: `modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/TrustedIntrinsicRegistryTest.kt`
- Test: `modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt`

- [ ] **Step 1: Add RED SDK tests**

Compile a non-suspending program using every required overload:

```kotlin
import compukter.io.Stderr

fun main() {
    print("name: ")
    val name = readln()
    println()
    println(name)
    println(7)
    println(true)
    println('x')
    Stderr.write("done\n")
}
```

Assert `readln` lowers to an async capability continuation and both output endpoints remain sync operations.

- [ ] **Step 2: Run and verify RED**

```bash
./gradlew-sandbox :compiler-k2:test --tests '*TrustedIntrinsicRegistryTest*stdio*' --tests '*MinimalScriptLoweringTest*standard io*'
```

Expected: console declarations and `compukter/stdio@1` are missing.

- [ ] **Step 3: Bind the pinned Kotlin stdlib console identities**

Do not redeclare `kotlin.io` functions: they already come from the pinned Kotlin standard-library classpath, and a duplicate source declaration would be ambiguous. Extend callable identity with a closed origin enum:

```kotlin
internal enum class TrustedCallableOrigin {
    TRUSTED_SDK_SOURCE,
    PINNED_KOTLIN_STDLIB,
    PLAYER_SOURCE,
}
```

Resolve only exact pinned-stdlib symbols for `kotlin.io.print(String)`, `println()`, `println(String)`, `println(Int)`, `println(Boolean)`, `println(Char)`, and `readln()`. Lower scalar overloads through the existing canonical string conversion and then `stdio.write`; reject `Any?` and all other overloads. A player declaration with the same package/name remains `PLAYER_SOURCE` and is ordinary guest code. Define the trusted SDK source `compukter.io.Stderr.write(String)` over stdio operation 2.

- [ ] **Step 4: Bind the three stdio operations in Rust**

```text
compukter/stdio@1
op 0 async readLine() -> String
op 1 sync  write(String) -> Unit
op 2 sync  writeError(String) -> Unit
```

All payload and line bounds come from the execution/device profile. A canonical/raw ownership conflict produces a deterministic host failure; it never blocks the Minecraft thread.

- [ ] **Step 5: Run and commit**

```bash
./gradlew-sandbox :guest-api-core:test :compiler-k2:test
CARGO_TARGET_DIR=.toolchain/build/cargo/compukter-vm cargo test --manifest-path host/compukter-vm/Cargo.toml --locked --offline stdio -- --nocapture
git -C host/compukter-vm add src
git -C host/compukter-vm commit -m "feat(stdio): expose standard stream host ABI (#534)"
git add modules/guest-api-core modules/compiler-k2 host/compukter-vm
git commit -m "feat(guest-api): add standard I/O module (#534)"
```

## Milestone 5 — Shell and ROM Migration ([#535](https://github.com/CertifiedBadIdeas/Compukters/issues/535))

### Task 12: Implement the bounded POSIX-lite lexer

**Files:**
- Create: `system/programs/shell/Lexer.kt`
- Modify: `modules/compiler-k2/build.gradle.kts`
- Modify: `modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt`

- [ ] **Step 1: Add a complete RED lexer table**

```kotlin
assertLex("greet Ada \"Red Engineer\" '' pre\"fix value\"post", arrayOf("greet", "Ada", "Red Engineer", "", "prefix valuepost"))
assertLex("a\\ b 'c\\d' \"e\\\"f\"", arrayOf("a b", "c\\d", "e\"f"))
assertSyntaxError("unterminated '")
assertSyntaxError("trailing\\")
assertLex("  ", emptyArray())
```

Also cover adjacent empty segments, escaped backslash, tabs/newlines as whitespace, maximum token count, maximum token length, and maximum aggregate code units.

- [ ] **Step 2: Run and verify RED**

```bash
./gradlew-sandbox :compiler-k2:test --tests '*MinimalScriptLoweringTest*shell lexer*'
```

Expected: lexer symbols are unresolved.

- [ ] **Step 3: Implement one-pass bounded lexing**

```kotlin
sealed interface LexResult {
    data class Success(val words: Array<String>) : LexResult
    data class Error(val message: String) : LexResult
}
```

Use a `CharArray` token buffer plus an array of completed strings. States are `UNQUOTED`, `SINGLE_QUOTED`, `DOUBLE_QUOTED`, and `ESCAPED(previous)`. Adjacent segments append to one token; opening quotes marks a token present even when no characters follow. Check all bounds before appending and return a concrete diagnostic without starting a process.

- [ ] **Step 4: Run and commit**

```bash
./gradlew-sandbox :compiler-k2:test --tests '*MinimalScriptLoweringTest*shell lexer*'
git add system/programs/shell modules/compiler-k2
git commit -m "feat(shell): parse bounded structured arguments (#535)"
```

### Task 13: Migrate boot, shell, kotlinc, and edit

**Files:**
- Modify: `system/programs/boot.kt`
- Modify: `system/programs/shell.kt`
- Modify: `system/programs/kotlinc.kt`
- Modify: `system/programs/edit.kt`
- Modify: `modules/compiler-k2/src/test/kotlin/compukter/system/edit/EditProgramTest.kt`
- Modify: `modules/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt`

- [ ] **Step 1: Add RED program behavior tests**

Assert `boot` handles `ProcessResult`, shell resolves `/home` then `/rom` only after `NOT_FOUND`, malformed syntax never calls `Process.run`, `kotlinc` consumes `main(args)`, edit consumes its path from `args`, and every checked-in program has ordinary `fun main`:

```kotlin
assertEquals(arrayOf("demo.kt", "-o", "hello"), parsedKotlincArgs)
assertEquals("usage: edit <path>", editError(emptyArray()))
assertFalse(programSources.any { "suspend fun main" in it })
```

- [ ] **Step 2: Run and verify RED**

```bash
./gradlew-sandbox :compiler-k2:test --tests '*MinimalScriptLoweringTest*checked in*' --tests '*EditProgramTest*'
```

Expected: old programs still use `Process.commandLine`, integer results, capability `15`, and raw terminal input.

- [ ] **Step 3: Migrate programs to stdio and argv**

Use these entry shapes:

```kotlin
// boot.kt
fun main() { report(Process.run("/rom/shell")) }

// shell.kt
fun main() { while (true) { print("> "); dispatch(readln()) } }

// kotlinc.kt
fun main(args: Array<String>) {
    val parsed = parseArguments(args)
    if (parsed.error != "") Stderr.write(parsed.error + "\n")
    else compile(parsed.source, parsed.output)
}

// edit.kt
fun main(args: Array<String>) {
    if (args.size != 1) Stderr.write("usage: edit <path>\n")
    else edit(resolveUserPath(args[0]))
}
```

Shell keeps built-ins `help`, `echo`, `clear`, `pwd`, `ls`, and `stat`. External execution passes `words.copyOfRange(1, words.size)`. A non-zero `Exited` and every `Failed` result are written once through `Stderr`; failure diagnostics are not printed by Rust.

- [ ] **Step 4: Run deterministic program compilation GREEN**

```bash
./gradlew-sandbox :compiler-k2:test
git add system/programs modules/compiler-k2
git commit -m "feat(system): migrate ROM programs to process v2 (#535)"
```

### Task 14: Bump compiler/cache identities and regenerate ROM resources

**Files:**
- Modify: `modules/compiler-artifact/src/main/kotlin/ru/lazyhat/compukters/compiler/artifact/write/ArtifactWriter.kt`
- Modify: `modules/compiler-client/src/main/kotlin/ru/lazyhat/compukters/compiler/worker/controller/WorkerPayloadLoader.kt`
- Modify: `modules/compiler-k2/build.gradle.kts`
- Modify: `modules/compiler-client/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/controller/CompilationIdentityTest.kt`
- Modify: `modules/compiler-runtime/src/test/kotlin/ru/lazyhat/compukters/compiler/runtime/worker/PackagedWorkerPayloadTest.kt`
- Modify: `modules/v26_1/v26_1-common/src/test/kotlin/ru/lazyhat/compukters/minecraft/computer/SystemRomImageTest.kt`

- [ ] **Step 1: Add RED version-invalidation tests**

Assert the new writer/runtime ABI rejects the previous entry-record layout and that the worker identity changes when either process@2 or stdio@1 SDK source changes:

```kotlin
assertNotEquals(oldIdentity.cacheKey(), processV2Identity.cacheKey())
assertNotEquals(processV2Identity.cacheKey(), stdioV1Identity.cacheKey())
```

In the core integration test, install a checked format-major-1 artifact and assert the public mapping is `INCOMPATIBLE`, not `INVALID_PROGRAM`; the low-level decoder rejection itself was established in Task 2.

- [ ] **Step 2: Run and verify RED**

```bash
./gradlew-sandbox :compiler-client:test :compiler-runtime:test :v26_1-common:test
```

Expected: old and new identities currently collide or the old artifact decodes under v1.

- [ ] **Step 3: Perform one coordinated compiler/stdlib ABI bump**

Retain the artifact format-major bump already made in Tasks 1–2; do not bump it again. Bump compiler codegen ABI and standard-library ABI hashes in the worker payload identity for the new lowering and SDK contracts. Do not add a dual reader or process@1 adapter. Ensure the compilation cache already hashes the changed worker/SDK payload rather than adding an independent cache namespace.

- [ ] **Step 4: Regenerate all four extensionless resources**

```bash
./gradlew-sandbox :v26_1-common:processResources
```

Verify generated resources are exactly `system/programs/{boot,shell,kotlinc,edit}` and `rom/rom.index` contains their updated hashes.

- [ ] **Step 5: Run and commit**

```bash
./gradlew-sandbox :compiler-client:test :compiler-runtime:test :v26_1-common:test
git add modules
git commit -m "feat(abi): activate process v2 and stdio v1 (#530)"
```

### Task 15: Verify the complete playable loop

**Files:**
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukters/core/device/runtime/program/integration/ProgramRuntimeHostIntegrationTest.kt`
- Modify: `modules/v26_1/v26_1-neoforge/src/gameTest/kotlin/ru/lazyhat/compukters/impl/computer/ComputerBlockGameTest.kt`

- [ ] **Step 1: Add RED core integration scenarios**

Drive real terminal input through `boot -> shell` and assert:

```text
> kotlinc greet.kt
compiled: /home/greet
> greet Ada "Red Engineer" ''
name: Ada
team: Red Engineer
empty: true
status: 37
>
```

Add cases for malformed quotes, not found, incompatible old artifact, trapped child, process limit, canonical Backspace, and reboot retaining `/home/greet.kt` while clearing the partial input line and active wait.

- [ ] **Step 2: Run core integration RED, then make only boundary fixes**

```bash
./gradlew-sandbox :core:test --tests '*ProgramRuntimeHostIntegrationTest*'
```

Expected: failures identify any missing native/runtime plumbing. Fix only the concrete mappings, bounds, or lifecycle wiring exposed by these tests; do not expand scope to jobs, pipes, manifests, HALs, or peripheral discovery.

- [ ] **Step 3: Add the NeoForge GameTest**

Install source through the test VFS, type the same compile/run/input sequence through networked terminal events, and assert the terminal snapshot ends at a fresh shell prompt. Reopen with a second client before completion to verify both clients converge on the same Rust-owned terminal state.

- [ ] **Step 4: Run automated verification**

```bash
./gradlew-sandbox-dev-parallel verifyLocalFull --rerun-tasks
./gradlew-sandbox-dev-parallel :v26_1-neoforge:runGameTestServer
git diff --check
```

Expected: all commands exit `0`, Gradle reports `BUILD SUCCESSFUL`, and GameTest reports the process-v2 terminal test passed.

- [ ] **Step 5: Commit integration coverage**

```bash
git add modules/core modules/v26_1
git commit -m "test(process): verify the process v2 playable loop (#530)"
```

### Task 16: Close child issues and leave the parent ready for manual review

**Files:**
- Verify only

- [ ] **Step 1: Confirm repository and submodule state**

```bash
git -C host/compukter-vm status --short --branch
git status --short --branch
git log --oneline --decorate -12
```

Expected: both worktrees are clean; the development branch contains focused commits referencing #531–#535 and #530.

- [ ] **Step 2: Re-run static checks at the final commit**

```bash
CARGO_TARGET_DIR=.toolchain/build/cargo/compukter-vm cargo clippy --manifest-path host/compukter-vm/Cargo.toml --locked --offline --all-targets -- -D warnings
cargo fmt --manifest-path host/compukter-vm/Cargo.toml --check
./gradlew-sandbox-dev-parallel verifyLocalFast
git diff --check HEAD^
```

Expected: all commands exit `0`.

- [ ] **Step 3: Update Roadmap state outside the sandbox**

Close #531, #532, #533, #534, and #535 as completed and move their project items to Done only after their acceptance tests pass. Keep #530 in Now until the user manually confirms in Minecraft:

1. shell quoting and empty arguments;
2. `kotlinc` followed by execution of a `main(args)` program;
3. `readln`, Backspace, stdout, and stderr behavior;
4. non-zero exit and readable failure diagnostics;
5. reboot cleanup with retained `/home` source.

After that manual check, close #530 as completed and move it to Done.

## Explicitly Deferred Work

- Multiple runnable guest tasks, jobs, cancellation, signals, pipes, redirection, and general file descriptors.
- Project manifests, module selection, controller BSP/HALs, UART/serial bindings, addon SDK generation, and peripheral discovery.
- Per-process permission masks or untrusted guest sandbox policies.
- Production numerical limits before device classes and memory/performance benchmarks are defined.
- Compatibility execution for `compukter/process@1` or the previous artifact entry layout.
