---
layout: default
title: Verification
section: contributors
permalink: /VERIFICATION/
---

# Verification

Compukters uses different verification gates for fast feedback, a complete local checkout, and a distributable
multi-platform release. During development, choose focused evidence from the changed boundary. Reserve full-checkout
verification for release preparation or an explicit user request.

For long runs where console volume is undesirable, use `./gradlew-sandbox-dev-parallel-summary <tasks>`. It preserves
the complete combined log under `build/agent-logs/`, prints only the final Gradle summary on success, and emits a
bounded diagnostic summary and log tail on failure without changing the command's exit code.

## Evidence contract

A successful command proves only the checks that actually belong to its task graph and executed with their required
inputs. A dry-run proves task membership, not behavior. An expected mandatory scenario that was filtered out, skipped
because an input was absent, or otherwise did not execute is not passing evidence. A normal Gradle `UP-TO-DATE` result
is valid only to the extent that the owning task declares all behavior-relevant inputs.

Run evidence after the last relevant change. Record the exact command and result, and limit readiness claims to that
scope. Focused checks protect each development iteration and completed implementation stage. Completing a feature,
a multi-stage plan, or a change spanning multiple modules or repositories does not automatically require
`verifyLocalFull`. Broaden testing only to resolve a concrete remaining risk or at the user's request. Run
`verifyLocalFull` on the exact release candidate before declaring release preparation complete; fix failures and
repeat the affected checks before re-running the release gate.

Use the [in-world VM benchmark](https://certifiedbadideas.github.io/Compukters/VM-BENCHMARK/) for repeatable manual
profiles of aggregate runnable-computer cost. Its results are environment-specific performance evidence, not a
replacement for deterministic runtime and conformance checks.

The strict IDE visible-latency SLO test runs as `:v1_21_1-neoforge:visibleIdeLatencyPerformanceTest`
after `verifyLocalFunctional` completes in `verifyLocalFull`. It retains its median/p95, worker reuse,
incremental-update and memory assertions, but does not compete with parallel compilation, archive transformation
or GameTest servers. Ordinary module `check` does not run this machine-sensitive measurement;
the complete verification gate still requires it. For a focused measurement, run the task directly on an idle host.
Dedicated latency measurement tasks always execute rather than reusing cached results from another host or run.
The visible completion budget is 200 ms median and 350 ms p95; semantic presentation has a 450 ms median
and 800 ms p95 budget. Both include analysis, client tick observation and the first rendered frame.

Mod CI tests are temporarily disabled: the tag-only release workflow builds and validates archive composition
and inventory, excluding both packaged native integration test tasks. A successful workflow is packaging and
publication evidence, not a full verification result. The local verification commands remain available unchanged.

## Addon control latency

Run `./gradlew-sandbox-dev-parallel -p addons/dev runGameTestServer` to include
`CreativeVectorThrusterLatencyGameTests.snapshotToNozzle` and `snapshotToNozzleParallel`.
Each probe samples five steps from a real Guest Sable observation through vector/throttle calls to a real
Creative Vector Thruster. The parallel variant sends vector and throttle from separate Guest tasks.
The log records `Propulsion latency mode=...` rows with server-tick distances from the Sable observation to
observed target-vector and throttle changes, the subsequent state read, and 50%/90% nozzle response.
`throttle90` records 90% effective throttle after startup; `-1` means it was not observed in the sample window.

Observation runs on the server without additional Guest polling calls. A test-only subclass of the real
block entity also captures vector target updates through `setChanged` and throttle updates through
`setDigitalInput`. The `Propulsion mutation mode=...` rows report direct mutation ticks and the separate
poller's lag. This distinguishes actual delivery from observation one tick later.

The normal GameTest server runs unthrottled. Asynchronous worker results can miss a pre-tick pump in this
mode even when ordinary 20 TPS pacing would leave enough time; multiplying its tick counts by 50 ms does
not establish normal-world delivery latency. Repeat with ordinary pacing before making that claim.
Sable physics is paused to hold the construction in place while block-entity and VM ticks continue, so
these results cover transport and actuator-state response. Dynamic flight, force application, PID
stability, and loaded-world scheduling require separate measurements.
When comparing notified delivery, the paced harness must use the ordinary server managed idle loop,
which processes queued server tasks without advancing the world tick. Parking without processing tasks
or allowing notifications to shorten the 50 ms period measures a different scheduler.

## Hibernation across Minecraft processes

The ordinary hibernation GameTest normally exercises carrier replacement, server-store stop/reopen, retained editor
state and explicit reboot. Its optional two-phase fixture verifies the same unsaved editor in independent Minecraft
server processes. Run the phases sequentially from the checkout; retain the same GameTest world and payload path:

```bash
mkdir -p .agents/tmp
COMPUKTERS_HIBERNATION_RESTART_PHASE=produce \
COMPUKTERS_HIBERNATION_RESTART_PAYLOAD="$PWD/.agents/tmp/hibernation-restart.nbt" \
./gradlew-sandbox-dev-parallel-summary :v1_21_1-neoforge:runGameTestServer

COMPUKTERS_HIBERNATION_RESTART_PHASE=resume \
COMPUKTERS_HIBERNATION_RESTART_PAYLOAD="$PWD/.agents/tmp/hibernation-restart.nbt" \
./gradlew-sandbox-dev-parallel-summary :v1_21_1-neoforge:runGameTestServer
```

The first process stops with unsaved Guest editor text. The second loads the saved carrier metadata, resumes that
editor, appends text and saves/reopens the file. A fresh boot cannot satisfy its terminal assertions. Minecraft 26.1 GameTestMain deletes its
test universe at each startup, so this two-process recipe uses 1.21.1. The normal 26.1 GameTest independently covers
carrier replacement and server-store stop/reopen. These are orderly stop/start checks, not crash or power-loss recovery guarantees. Sable's separate `check runGameTestServer` run includes one- and
two-computer construction reloads with live physics and logs activation-to-Guest-continuation latency.

## Documentation site

The [Compukters API reference]({{ '/guest-api/' | relative_url }}) is generated by Dokka with separate
[Compukters Core]({{ '/guest-api/guest/' | relative_url }}),
[Create addon]({{ '/guest-api/create/' | relative_url }}),
[Sable addon]({{ '/guest-api/sable/' | relative_url }}) and
[Propulsion addon]({{ '/guest-api/propulsion/' | relative_url }}) modules. The publication and source-set target are named
`Compukters`. Addon signatures link to types from the core mod;
each module links to its own source files. For local preview, run
`./gradlew-sandbox-dev-parallel-summary -p api-build dokkaGeneratePublicationHtml`, then
`python3 docs/_tools/build_guest_api.py`, `python3 docs/_tools/build_ide_palette.py`, then
`jekyll build --source docs --destination /tmp/compukters-site`.
The generated `docs/guest-api/` directory is ignored
by Git; `python3 docs/_tools/build_guest_api.py --check` verifies its files against the Dokka output.
The Wiki's syntax palette is generated from the in-game `IdeColors.kt` and committed as
`docs/_data/ide_palette.json`; `python3 docs/_tools/build_ide_palette.py --check` detects a stale projection.
Documentation CI regenerates it and also runs when the IDE color source changes. The browser uses ordinary CSS;
Rouge supplies lexical token classes during the Jekyll build.

## Verification levels

The Gradle task view keeps Compukters entrypoints separate from tasks generated by Java, Kotlin, Loom, and publishing
plugins. Use the `Compukters development`, `distribution`, `verification`, `addon sdk`, `native`, `tooling`,
`system programs`, `maintenance`, and `release` groups for ordinary repository work. Individual checks used by the
aggregate verification gates live under `Compukters verification details`.

| Command | Claim it supports | It does not establish |
| --- | --- | --- |
| `./gradlew-sandbox-dev-parallel verifyLocalFast` | Policy and build-script checks plus the curated fast JVM slice | Complete module, VM, GameTest, or release coverage |
| `./gradlew-sandbox-dev-parallel verifyAllModuleChecks` | Every current Gradle subproject `check` lifecycle | Host Rust crate checks, Kotlin-to-VM conformance, or the real GameTest server |
| `./gradlew-sandbox-dev-parallel verifyKotlinVmConformance` | Every execution-conformance scenario registered through the root build's conformance registration path | Unrelated JVM, IDE, Minecraft, or release behavior |
| `./gradlew-sandbox-dev-parallel-summary verifyLocalFull` | Complete Compukters checkout: all subproject checks, registered conformance, Rust/FFI/JNI and Runtime-version consistency, integrations, real GameTests, and both production artifacts for the locally configured native platform | The separately built Create addon, clean-tag state, or Linux-and-Windows universal release readiness |
| `./gradlew-sandbox-dev-parallel :v26_1-neoforge:buildProductionUniversalJar` | Official-name production JAR and archive checks for the configured local native resources | A clean tagged universal release or unconfigured target platforms |
| `./gradlew-sandbox-dev-parallel :v1_21_1-neoforge:buildProductionUniversalJar` | Remapped Minecraft 1.21.1 production JAR, Java 21 JNI runtime, metadata, and archive resources | Visual UI behavior, cross-version feature parity, or multi-platform release readiness |
| `./gradlew-sandbox-dev-parallel-summary -p addons/dev collectDistributionJars` | Both local production mod archives and the three first-party addon archives, including archive/version checks, synchronized into root `dist/` | Full module tests, addon GameTests, third-party dependencies, or tagged multi-platform release readiness |
| `./gradlew-sandbox-dev-parallel-summary -p addons/dev collectReleaseDistributionJars` | Both clean exact-tag release gates using pinned Linux/Windows Runtime bundles, plus all three addon archive checks, synchronized into root `dist/` | Full candidate/module tests, addon GameTests, third-party dependencies, or external publication |
| `./gradlew-sandbox-dev-parallel-summary :v1_21_1-neoforge:buildReleaseUniversalJar` | Clean exact-tag release state, Linux and Windows JNI resources, 1.21.1 archive contents, and packaged JNI execution | The 26.1.2 artifact or external publication |
| `./gradlew-sandbox-dev-parallel-summary :v26_1-neoforge:buildReleaseUniversalJar` | Clean exact-tag release state, Linux and Windows FFI resources, 26.1.2 archive contents, and packaged FFM execution | The 1.21.1 artifact or external publication |
| `./gradlew-sandbox-dev-parallel-summary buildReleaseUniversalJar` from the repository root | Gradle selects both version-specific tasks and builds both distributable JARs from the same validated dual-transport Runtime bundles on a clean exact tag | Publication, upload, push, or external release creation |

The production task retains its historical `UniversalJar` name, but it selects universal native bundles only when the
release runtime bundle configuration is present. Use the release task—not the production task name—as the universal
release gate.

## Boundary matrix

The commands below guide selection of evidence for affected boundaries; run only the checks relevant to the change.
Include behavioral tests for malformed input, limits, lifetime, or failure cases when those contracts are affected.

See [Native runtime test coverage](https://certifiedbadideas.github.io/Compukters/NATIVE-TEST-COVERAGE/) for the semantic ownership matrix across direct Rust,
FFI, Kotlin-to-VM conformance, runtime-host integration, and NeoForge GameTests.

| Changed boundary | Development evidence | Before release |
| --- | --- | --- |
| Guest Kotlin declarations, platform metadata, K2 lowering, or IDE semantics | Focused tests and lint in affected `guest-platform`, `platform-bundle`, `platform-k2`, `compiler-k2-engine`, `compiler-k2`, or `ide-*` modules; the applicable Kotlin-to-VM conformance scenario for an execution claim (portable math: `testKotlinMathVmConformance`) | `verifyLocalFull` on the release candidate |
| `.cpkt` model, encoding, instruction, verifier, or admission contract | `:compiler-artifact:check`, affected compiler checks, focused VM tests, and the applicable conformance scenario; include malformed and version behavior | `verifyLocalFull` on the release candidate |
| Worker protocol, payload, isolation, or tooling bundle | Owning client/server module `check`, forked-worker checks, payload/license verification, and wrong-version or malformed framing cases | `verifyLocalFull` on the release candidate |
| Rust VM execution, managed memory, quotas, terminal, filesystem, or persistence | Focused `cargo test --manifest-path host/compukter-vm/Cargo.toml --locked --offline` target or test, plus the owning JVM adapter check when observable there | `verifyLocalFull` on the release candidate |
| Native runtime API, Rust C ABI, JDK FFM adapter, or Java 21 JNI adapter | `:native-runtime-api:check`, focused FFI/JNI Rust checks, the owning `:native-runtime-ffm:verifyNativeRuntime` or `:native-runtime-jni:verifyNativeRuntime`, layout/error/lifetime cases, and a real JVM-to-native integration | `verifyLocalFull` on the release candidate |
| Loader-independent computer/runtime behavior | `:core:check`; include `programRuntimeIntegrationTest` when VM behavior participates | `verifyLocalFull` on the release candidate |
| Minecraft lifecycle, registration, persistence adapter, redstone, or server-visible networking | Owning common/NeoForge checks and `:v26_1-neoforge:runGameTestServer` when world or lifecycle behavior changes | `verifyLocalFull` on the release candidate |
| Standalone Create addon registration, real devices, or named peripheral lifecycle | From `addons/create`, `./gradlew-sandbox-dev-parallel-summary check` and `./gradlew-sandbox-dev-parallel runGameTestServer`; base `:v1_21_1-neoforge:runGameTestServer` independently verifies no-Create behavior | Base and addon production archive checks; visual cable/configurator observation when required |
| Client UI, input, rendering, or other inherently visual behavior | Owning module checks plus a development-client scenario with the observation recorded | `verifyLocalFull` on the release candidate plus the required manual observation |
| Metadata, resources, native packaging, access transformers, or archive composition | `:v26_1-neoforge:buildProductionUniversalJar` and inspection produced by its verification tasks | `verifyLocalFull` on the release candidate |
| Tagged distributable release | `verifyLocalFull` on the exact candidate revision before tagging | `buildReleaseUniversalJar` from the repository root on the clean exact tag with the published Runtime bundles |

## Manual client scenarios

Require a development-client observation only when the changed contract is inherently visual or interactive and
cannot be established by a lower automated layer. Examples include rendering, screen layout, focus, keyboard or mouse
interaction, and the visible result of reconnecting or opening a viewer. Do not require a client run for VM, compiler,
server lifecycle, networking-state, or persistence behavior that focused tests or GameTests can prove directly.

Before launching the client, write down a bounded scenario containing:

- the clean test world and initial state;
- the exact player actions;
- the observable expected result;
- a timeout or clear completion condition;
- the log, screenshot, or short recording needed to preserve the observation when it matters to review.

Run manual scenarios on a disposable test world, not a user save. If the agent cannot control or observe the client,
report the scenario as pending manual evidence instead of treating a successful client launch as verification. Promote
a recurring scenario to GameTest or another automated test when the behavior becomes observable without subjective
visual judgment.

## Release evidence

For an artifact or release-readiness claim, record:

- the parent repository revision and pinned `host/compukter-vm` revision;
- the exact produced JAR path and its digest when it will leave the local checkout;
- the packaged native resource paths and whether they came from local development output or configured release bundles;
- the verification commands run from that revision.

Building or verifying an artifact does not authorize creating a tag, pushing commits, uploading files, or publishing a
release. Those external mutations require an explicit user request.
