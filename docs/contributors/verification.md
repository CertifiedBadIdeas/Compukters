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

## Configuration Cache

Configuration Cache is enabled by default for the main repository and the `addons/dev` composite build.
Compatibility errors remain fatal; use `--no-configuration-cache` only when diagnosing whether a failure
depends on graph replay. Configuration Cache allows independent built-in compiler tasks to execute concurrently
even within the `compiler-k2` project, including on the first cache write. `--parallel` alone only enables
parallel execution across projects. The top-level build selects these settings; an included build does not
enable Configuration Cache for its caller. Use `--max-workers=N` to bound concurrent work. Local packaging with
`:v1_21_1-neoforge:remapJar :v26_1-neoforge:jar` has also been exercised with the cache; these packaging
tasks alone do not verify production archive contents or release readiness.

The `build` and `verifyLocalFull` graphs have also been exercised for strict cache storage and replay. JVM
parameters are configured before task execution; isolation and archive checks consume declared dependency metadata
and file collections. The media inventory retains a dynamic file tree, so newly added assets are still checked on
cache replay. Generated guest bundles, host contracts and built-in programs remain declared task outputs.

Cache compatibility does not imply a passing build: ordinary compilation, runtime and GameTest failures still fail
the command. [Issue #744](https://github.com/CertifiedBadIdeas/Compukters/issues/744) tracks the global rollout and
remaining functional verification blockers. Strict serialization also covers workspace cleanup, dependency staging
and both targets' development client/server graphs. Development settings preserve manual options and existing
client server lists on replay. Tagged release graphs still require their separate gate and a compatible published
Runtime descriptor.

```sh
./gradlew-sandbox-dev-parallel-summary :compiler-k2:generateKotlincArtifact :compiler-k2:generateShellArtifact
./gradlew-sandbox-dev-parallel-summary :v1_21_1-neoforge:classes :v26_1-neoforge:classes
./gradlew-sandbox-dev-parallel-summary :tooling-runtime:verifyToolingRuntimeBundle
./gradlew-sandbox-dev-parallel-summary build
./gradlew-sandbox-dev-parallel-summary verifyLocalFull
```

The first run stores the task graph; repeat the same command and check for `Reusing configuration cache`.
Initial creation of `versions.properties` or generated Minecraft metadata can require another cache write before
reuse stabilizes. Build-version selection tracks Git tags on `HEAD`, so adding or moving a release tag invalidates
the cached configuration when its output changes. Do not suppress Configuration Cache errors for an unverified
graph, or treat successful cache storage as successful functional verification.

## Agent build limits

The main sandbox wrappers and `addons/dev` share one workspace build slot across linked worktrees.
Competing builds wait before cache preparation or JVM startup. The OS releases the slot on process
exit, including failures. Existing running builds and older checkouts without these wrappers are not
retroactively limited. Direct `./gradlew` and standalone addon wrappers do not acquire the slot.

Managed builds default to eight Gradle workers and eight Rust build jobs. Set `COMPUKTERS_GRADLE_WORKERS`
and `COMPUKTERS_RUST_JOBS` to positive integers to change the defaults. Explicit `--max-workers=N`
and `-PcompukterVmBuildJobs=N` arguments override them. The Gradle JVM heap remains 4 GiB; these limits
bound concurrency, not the total memory of child processes.

Run a standalone addon through the same slot from the parent checkout with:

```sh
(cd addons/create && python3 ../../tools/gradle/run.py --root ../.. ./gradlew-sandbox check)
```

## Gradle caches in agent worktrees

The sandbox wrappers keep each checkout's writable Gradle home in `.gradle-sandbox`. New homes are automatically
initialized from one workspace template with `cp --reflink=always`: cache files have separate inodes and locks, while
unchanged disk extents remain shared. Build outputs and project `.gradle` directories also remain local. Gradle runs
in different worktrees can proceed independently; competing runs in the same checkout still use Gradle's normal
project locks. Parallel builds also compete for the host's CPU and memory.

The wrappers require Python 3, GNU `cp`, `flock`, and a filesystem supporting reflinks between the template and
checkout, such as Btrfs. They never fall back to full copies or hard links when cloning fails. The default template
is `.toolchain/gradle-cache/template` in the main Git checkout, discovered through Git's common directory even from
a linked worktree. Set `COMPUKTERS_GRADLE_CACHE_TEMPLATE` to use another template location on the same filesystem.

No manual cache preparation is required. If the template is missing, the first wrapper invocation looks for idle
populated homes in the workspace's existing worktrees and `~/.gradle`, preferring those with Minecraft caches. It
publishes a template through reflinks and initializes the new home. If no source is available, the first build starts
with an empty home and publishes the downloaded cache after success. Deleting a worktree home causes the next run
to recreate it from the template; deleting the template causes automatic bootstrap again. With no caches anywhere,
the first build must download its dependencies and distribution.

To select bootstrap sources explicitly, set `COMPUKTERS_GRADLE_CACHE_SOURCES` to a colon-separated list of Gradle
homes. An empty value disables discovery, which is useful for checking a genuinely cold first build.

You can also publish or refresh the template explicitly from an **idle** populated Gradle home:

```sh
./gradlew-sandbox-cache seed /path/to/populated-worktree/.gradle-sandbox
```

An idle `~/.gradle` can also serve as the source if it is on the same filesystem. The command clones `caches/` and
`wrapper/`, including Minecraft plugin caches and Gradle distributions. It excludes process locks, temporary files,
the access journal, daemons, local init scripts and user credentials/configuration. Symlinked cache content is rejected
to preserve isolation. Managed wrappers hold a per-home shared guard throughout each build; publication requires
that guard exclusively and rejects busy sources instead of waiting. Native Gradle locks provide an additional check
for direct invocations, but an unmanaged source must stay idle for the entire operation. Do not start an IDE Gradle
sync or direct `gradlew` against that source while seeding.

Template publication replaces an immutable generation atomically; readers see a complete generation. After a
successful build, a home based on the current generation automatically refreshes the template. A home based on an
older generation preserves its local additions without replacing a newer template published by another build.
The explicit seed command can publish such a home when needed. Template maintenance failure is reported without
changing the build's exit status. Existing writable homes are preserved; only subsequently initialized homes use
the new template. Old template generations are removed after readers finish cloning. Existing caches are not
deleted or migrated automatically.

Without a template, the wrapper announces an empty home and Gradle populates it normally. Internet access is needed
for missing distributions and dependencies; seeding from a sufficiently populated home avoids those downloads.
Gradle's `--offline` applies to dependency resolution and does not prevent the wrapper downloading a missing
distribution. New or modified cache files consume additional space independently in each worktree.

Ordinary `du` sums reflink files as though their data were separate. On Btrfs, inspect shared and exclusive extents:

```sh
btrfs filesystem du -s .gradle-sandbox /path/to/main-checkout/.toolchain/gradle-cache/template
```

Use `df` to measure total filesystem allocation; do not estimate it by multiplying a home's apparent size by the
number of worktrees. Wrapper regression checks run with the build-script tests, or directly with
`python3 -m unittest discover -s tools/gradle -p 'test_*.py' -v`. Reflink-dependent cases skip on unsupported test
filesystems; the unsupported-clone failure path remains separately covered on a reflink-capable checkout.

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

Use the [in-world VM benchmark]({{ '/VM-BENCHMARK/' | relative_url }}) for repeatable manual
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
each addon module links to its own repository at the checked-out revision. Pages CI initializes
the three addon submodules at each documentation version's pinned revisions. For an unversioned local preview, first run
`git submodule update --init addons/create addons/sable addons/propulsion`, then
`./gradlew-sandbox-dev-parallel-summary -p api-build dokkaGeneratePublicationHtml`, then
`python3 docs/_tools/build_guest_api.py`, `python3 docs/_tools/build_ide_palette.py`, then
`jekyll build --source docs --destination /tmp/compukters-site`.
The generated `docs/guest-api/` directory is ignored
by Git; `python3 docs/_tools/build_guest_api.py --check` verifies its files against the Dokka output.
The Wiki's syntax palette is generated from the in-game `IdeColors.kt` and committed as
`docs/_data/ide_palette.json`; `python3 docs/_tools/build_ide_palette.py --check` detects a stale projection.
Documentation CI regenerates it for each checkout. The browser uses ordinary CSS; Rouge supplies lexical token
classes during the Jekyll build.

### Release and nightly versions

The documentation selector offers `0.5.1` at `/Compukters/0.5.1/` and `Nightly` at `/Compukters/nightly/` on both guides
and generated API pages. The release site uses the exact `v0.5.1` checkout, including its addon revisions and API
sources. Nightly uses the current `dev` commit and displays a link to that commit. The Pages workflow runs on every
`dev` push and on manual dispatch; both versions are published together in one artifact.

Switching versions opens the same page when present, retaining its fragment with JavaScript enabled. If the page is
absent, the selector opens the chosen version's home. The links work without JavaScript. Existing unversioned URLs
redirect to `0.5.1`; pages available only in nightly redirect there. Redirects preserve query strings and fragments
with JavaScript enabled. A failed build prevents publication of either version.

For a full local preview, use a separate `v0.5.1` checkout and initialize its pinned addon submodules. Generate and
stage Dokka output and the syntax palette in **both** checkouts using their own build commands above. Then run the
current documentation tooling from the nightly checkout (`/path/to/release-checkout` is the separate tag checkout):

```sh
python3 -m unittest discover -s docs/_tools -p 'test_versioned_docs.py'
python3 docs/_tools/build_versioned_docs.py prepare --checkout . --version nightly --output build/docs-sources/nightly
python3 docs/_tools/build_versioned_docs.py prepare --checkout /path/to/release-checkout --version 0.5.1 --output build/docs-sources/0.5.1
jekyll build --source build/docs-sources/0.5.1 --config build/docs-sources/0.5.1/_config.yml --destination build/docs-sites/0.5.1
jekyll build --source build/docs-sources/nightly --config build/docs-sources/nightly/_config.yml --destination build/docs-sites/nightly
python3 docs/_tools/build_versioned_docs.py compose --release-source build/docs-sources/0.5.1 --release-site build/docs-sites/0.5.1 --nightly-source build/docs-sources/nightly --nightly-site build/docs-sites/nightly --output build/docs-published
```

Use fresh staging and composition output directories for each run; the tooling refuses to overwrite existing ones.
Serve the composed directory at `/Compukters/` to match its base URL. `prepare --baseurl` supports another deployment
prefix. Each version includes `version.json` with its source revision. The composition step uses the actual rendered
page inventories, rewrites hardcoded site links and source links to their version, and checks that both APIs exist.
It leaves tagged Markdown and Kotlin sources unchanged.

When adding a published documentation version, update `VERSIONS` in `docs/_tools/build_versioned_docs.py`, the release
checkout and build steps in `.github/workflows/pages.yml`, and the routing tests together. Never substitute current
`dev` content for a release checkout.

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

See [Native runtime test coverage]({{ '/NATIVE-TEST-COVERAGE/' | relative_url }}) for the semantic ownership matrix across direct Rust,
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
