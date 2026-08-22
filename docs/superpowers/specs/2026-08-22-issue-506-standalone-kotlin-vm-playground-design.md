# Standalone Kotlin-to-Compukter VM Playground Design

> Issue: [#506](https://github.com/CertifiedBadIdeas/Compukters/issues/506)

## Purpose

Build the first developer-facing vertical slice of the Compukter language platform outside Minecraft. A developer opens an ordinary multi-file Kotlin project with normal editor highlighting, runs one repository command, compiles the project in the isolated pinned K2 worker, verifies the resulting canonical Artifact v1, executes it on the real Rust VM through JNI, and interacts through terminal standard input and output.

The playground is not a disposable demo. It is the first integration harness for the same compiler, VM, capability, and add-on boundaries that the mod will use later.

## Accepted Product Shape

The first example is an ordinary Kotlin source project, not a Kotlin script:

```text
examples/hello/
├── compukter.toml
├── main.kt
└── greeting.kt
```

The entry point is exactly one zero-argument function returning `Unit`:

- `fun main()` for a non-suspending program; or
- `suspend fun main()` when the entry point directly calls suspending APIs.

The interactive example uses the second form:

```kotlin
suspend fun main() {
    print("Your name: ")
    val name = readln()
    println(greeting(name))
}
```

Player-shared projects and libraries are source-based. Canonical Artifact v1 files are derived build products and local cache entries, not the canonical player exchange format. A future compiled-library format may coexist with source bundles, but it is not required here.

Syntax highlighting comes from existing Kotlin support in IntelliJ IDEA or VS Code. This issue does not build a temporary standalone editor. The project model and diagnostics are designed for later reuse by the in-game IDE.

## End-to-End Architecture

```text
Kotlin project snapshot
        |
        v
playground JVM application
        |
        +--> lightweight compiler client
        |       |
        |       +--> isolated K2 worker payload
        |               |
        |               +--> Compukter IR lowering
        |                       |
        |                       +--> canonical Artifact v1
        |
        +--> native-runtime Kotlin API
                |
                +--> Compukters-owned JNI adapter crate
                        |
                        +--> host-neutral compukter-vm session
                                |
                                +--> asynchronous capability requests
                                        |
                                        +--> standalone terminal host
```

The same Kotlin host API and JNI adapter will be usable by Minecraft. The standalone terminal and the future in-game terminal are different host implementations of the same capability ABI.

## Compiler Process Boundary

The current `compiler-k2` module contains both the lightweight controller/protocol and the K2 implementation dependencies. A launcher must not depend on that combined runtime because doing so would place the Kotlin compiler in the launcher or Minecraft JVM.

Split the boundary into:

- `compiler-client`: protocol models/codecs, payload validation, worker process control, limits, compilation identity, and source snapshot models. It has no Kotlin compiler implementation dependency.
- `compiler-k2`: the isolated worker server, pinned K2 dependencies, compiler adapter, plugin registration, IR lowering, and Artifact v1 writer.

The worker distribution remains a fixed, measured payload. The client launches it lazily with the same controlled JVM policy established by #505. Application and mod runtime classpaths must continue to prove that they contain no K2 compiler implementation artifacts.

## Project Snapshot and Compilation Identity

Replace the single-script request with a bounded canonical project snapshot. It contains:

- a sorted collection of normalized relative `.kt` paths and strict UTF-8 source bytes;
- an entry-point declaration policy;
- target/language/codegen identities;
- the enabled trusted API and add-on bundle identities;
- explicit per-file, file-count, total-source, diagnostic, output, and temporary-storage limits.

Absolute paths, empty segments, `.`/`..`, backslashes, duplicate normalized paths, symlinks, special files, non-`.kt` sources, and guest-supplied classpaths or plugins are rejected before worker launch.

Compilation identity is domain-separated and covers the ordered path/content pairs, target settings, compiler payload, compiler/language/codegen versions, standard-library ABI, artifact-writer version, and trusted extension bundle identities. File-system enumeration order cannot change the identity or artifact.

The worker compiles all files in one K2 session. It accepts exactly one supported `main` entry point and returns source-positioned diagnostics using virtual paths and UTF-16 offsets.

## Kotlin API and Intrinsic Extension Model

Introduce a small compile-time standard API, initially containing the terminal declarations:

```kotlin
suspend fun print(value: String)
suspend fun println(value: String)
suspend fun readln(): String
```

These declarations are not JVM implementations. The custom backend resolves their exact trusted symbols and lowers them to versioned capability operations. It must never recognize intrinsics by unqualified textual function name alone.

The familiar unqualified names should resolve through the Compukter compilation environment. They must not silently bind to non-suspending JVM `kotlin.io` implementations. The implementation may use a restricted compile-time standard-library surface or controlled default imports, but the resolved symbol identity must be unambiguous and tested.

Two extension trust layers remain separate:

1. A guest library is ordinary Kotlin source in the bounded project snapshot. It is unprivileged, compiled with the program, and runs under the same VM verification and quotas.
2. A trusted add-on is installed by the server owner and may contribute versioned Kotlin API declarations, capability descriptors, and a trusted intrinsic-lowering provider through an explicit SDK. It becomes part of the controlled worker payload/identity. Guest code cannot provide its JAR, compiler plugin, classpath, or trust declaration.

#506 implements only the built-in terminal provider, but it uses the same registry seam intended for trusted add-ons. Pure intrinsics may remain ordinary functions. Any capability operation that can wait is declared `suspend`, allowing Kotlin type checking to reject calls from non-suspending code.

## Initial Lowering Surface

The current one-constant lowering is replaced only far enough to run the committed example. The required first subset is:

- top-level zero-argument `main` returning `Unit`, ordinary or suspending;
- direct calls between supported project functions;
- local immutable values;
- `String` literals and parameters/results;
- bounded String concatenation required by the example;
- calls to the three trusted terminal intrinsic symbols;
- straight-line suspension and resume points in a root suspend function;
- ordinary return and deterministic failure for unsupported IR.

General Kotlin/JVM compatibility, arbitrary coroutine builders, suspend lambdas, concurrent coroutines, reflection, annotations, class loading, broad standard-library lowering, and arbitrary object-oriented source constructs are not implied by this slice. Unsupported valid Kotlin IR produces a stable target diagnostic and no artifact.

## Host-Neutral VM Execution API

Compukter-VM issue [#43](https://github.com/CertifiedBadIdeas/Compukter-VM/issues/43) owns the runtime prerequisite after heap/string issue #42. The VM remains independent of JNI, Kotlin, Minecraft, mod loaders, and concrete capability providers.

The public Rust API admits only a fully verified artifact under an explicit bounded execution profile, creates a session, starts its entry point, and advances through charged guest and maintenance slices. Each advance returns one distinct outcome:

- slice exhausted;
- asynchronous host request;
- halted;
- guest trap;
- VM fault.

A host request contains an opaque request ID, capability namespace/name/ABI, operation ID, and bounded typed arguments. The host later supplies an explicit response using the same request ID. The initial single-task VM permits at most one outstanding request. Stale, duplicated, mismatched, oversized, or wrongly typed responses are rejected without corrupting the session.

The VM never calls host handlers directly and never blocks a host thread. Immediate and delayed responses must preserve the same guest-visible ordering, deterministic trace, and accounting.

## Terminal Capability ABI

The first capability is conceptually:

```text
compukter.terminal@1
├── write(text: String): Unit
├── writeLine(text: String): Unit
└── readLine(): String
```

All operations use asynchronous request/response instructions at the VM boundary. In Kotlin source they are suspending sequential calls. The next source statement executes only after the matching response.

The standalone host may acknowledge writes immediately after bounded output succeeds. Minecraft may acknowledge on a later tick or after forwarding output to a client. Waiting for acknowledgement preserves program order and provides backpressure. A future explicitly buffered API can make different guarantees without changing these operations.

`readLine` suspends until the host returns a line or a host failure. The first version reports terminal EOF as a structured runtime diagnostic and non-zero process exit rather than pretending that it is an empty line. Kotlin exception behavior can replace that temporary surface when exception unwinding and the standard runtime support it.

## String Boundary

Guest `String` follows Kotlin UTF-16 code-unit semantics. VM issue #42 supplies immutable literals and dynamic strings; #43 publishes bounded address-free host marshaling.

The initial runtime representation may be an immutable standard-library object backed by UTF-16 storage. Literal materialization, `readln()` results, concatenation, and terminal arguments all use that ABI. Internal compact-string optimizations may change storage later without changing Kotlin source or capability contracts.

The standalone terminal uses UTF-8 at the process boundary and converts to/from guest UTF-16 explicitly. Invalid UTF-8 input and unpaired UTF-16 output use deterministic `U+FFFD` replacement. `readln()` removes the recognized line terminator. Every conversion is limited before allocation or output.

## JNI Adapter

JNI support lives in a separate Compukters-owned Rust crate, not in `compukter-vm`. The adapter depends on the pinned VM submodule and exports a narrow handle-based API used by `native-runtime` Kotlin code.

JNI operations cover:

- verify/admit/create session;
- start and run one bounded slice;
- retrieve a copied bounded outcome or host request;
- resume with a copied bounded response;
- close an opaque session handle.

Native addresses and internal VM references never enter Kotlin. Handles include validation against stale or double-close use. The bridge performs no callbacks into Kotlin while holding VM state, and host dispatch happens after JNI returns. JNI exceptions represent bridge misuse or platform faults; guest traps and ordinary VM outcomes remain typed result values.

The Kotlin `native-runtime` facade owns lifecycle and maps the low-level result union into stable host models. It also owns the capability registry used by the standalone launcher and later by Minecraft/add-ons.

## Playground CLI

The repository command is:

```bash
./gradlew :playground:run --args examples/hello
```

An optional developer flag emits the derived artifact:

```bash
./gradlew :playground:run --args="examples/hello --emit build/hello.cpkt"
```

The lifecycle is:

1. Load and validate the bounded project snapshot.
2. Compile through the isolated worker.
3. Verify canonical Artifact v1 bytes.
4. Admit the artifact under a fixed playground execution profile.
5. Run bounded VM slices.
6. Dispatch terminal capability requests and resume the session.
7. Halt successfully or print a concise structured failure.

Normal output goes to stdout. Diagnostics go to stderr. JVM stack traces are hidden unless `--debug` is supplied.

Distinct non-zero exit categories cover project/input validation, compilation, artifact verification, VM admission, guest trap, VM fault, host capability/EOF failure, quota exhaustion, and launcher/platform failure. Exact numeric assignments are implementation detail but must be documented and tested.

## Resource, Security, and Determinism Rules

The playground is a development profile, not an unbounded bypass. It fixes limits for source files/bytes, compiler frames/diagnostics/temp storage/time/memory, artifact size, VM heap/stack/call depth/slice cost, host requests, response strings, total stdin/stdout, and resume count.

Guest-controlled data cannot select compiler arguments, plugins, classpaths, worker executables, JNI libraries, capability implementations, or add-on identities. Artifact verification and VM admission always occur even for a just-produced local artifact. Native and worker crashes become bounded launcher failures.

The source snapshot, compiler payload, enabled trusted APIs, artifact, execution profile, and capability ABI are all explicit identities. A repeated non-interactive run with identical input and host responses must produce the same artifact and deterministic VM trace/accounting.

## Verification Strategy

Focused tests include:

- canonical multi-file snapshot ordering, limits, path rejection, and identity;
- compiler-client runtime isolation from K2 implementation jars;
- ordinary and suspend entry-point discovery, duplicates, and invalid signatures;
- trusted-symbol intrinsic recognition and spoof rejection;
- String literal, concatenation, call, terminal operation, and straight-line suspend lowering;
- stable compiler diagnostics for unsupported IR;
- Rust public-session outcomes and exact request/resume conformance;
- immediate versus delayed response equivalence;
- stale ID, duplicate response, wrong type, oversize response, host failure, and quota cases;
- UTF-8/UTF-16 conversion and invalid-sequence behavior;
- JNI lifecycle, invalid/stale handles, bounds, concurrency rejection, and native error mapping;
- end-to-end compilation, verification, JNI execution, prepared stdin, and exact stdout;
- manual interactive execution of the documented example;
- relevant locked offline VM suites and repository fast verification.

Performance evidence records cold/warm compilation, JNI transition count/cost, VM slices, host-request latency, and total example startup. The first implementation favors correct reusable boundaries over premature batching, while retaining enough measurements to guide later optimization.

## Delivery Order and Roadmap Decomposition

1. Complete Compukter-VM #42, including its dynamic String and intrinsic-string requirements.
2. Implement Compukter-VM #43: public host-neutral sessions, asynchronous capability request/resume, and bounded String marshaling.
3. Split the lightweight compiler client from the isolated K2 worker.
4. Add bounded multi-file `.kt` snapshots and both supported `main` forms.
5. Add the compile-time standard API, intrinsic registry, and minimal lowering surface.
6. Add the independent JNI adapter and Kotlin native-runtime facade.
7. Add the playground application, example project, run configuration/documentation, and end-to-end tests.

VM commits use their exact VM Roadmap issues. Compukters commits and this integration design reference #506. #506 remains open until the documented standalone command compiles and interactively executes the real example through the real isolated compiler, verifier, JNI adapter, and VM.

## Out of Scope

- Minecraft or mod-loader startup and in-game UI integration;
- a new standalone editor, full IDE, debugger, shell, package manager, or repository;
- compiled artifacts as the canonical player-sharing format;
- complete add-on loading/distribution or arbitrary third-party compiler plugins;
- arbitrary Kotlin/JVM execution or broad Kotlin lowering coverage;
- general coroutines, concurrent guest tasks, or multiple outstanding host requests;
- production installer/distribution packaging;
- bypassing artifact verification, VM admission, or quotas for development convenience.
