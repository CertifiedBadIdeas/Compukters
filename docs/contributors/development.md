---
layout: default
title: Build and run Compukters
description: Set up the checkout, choose a Minecraft target, and run the mod or standalone playground.
section: contributors
permalink: /DEVELOPMENT/
---

# Build and run Compukters

Start from the [Compukters repository](https://github.com/CertifiedBadIdeas/Compukters). Clone with submodules, or run
`git submodule update --init --recursive` in an existing checkout. The pinned Rust VM is part of the build.

Minecraft builds use Architectury Loom for dependencies, mappings and development runs. Shared Minecraft modules
are packaged directly through their ordinary JAR variants (`namedElements` on 1.21.1) and the project's Shadow
conventions; they do not
use Architectury Plugin transformations or its runtime Java agent. Register shared source sets through `loom.mods`
when adding a Minecraft module. The Minecraft 1.21.1 production archive is remapped after merging its dependencies.

## Agent task worktrees

Create a ready task checkout with one command:

```sh
./worktree-task feat/display-api
```

It creates a new branch from the current **local** `dev` under the main workspace's `.worktrees/feat-display-api`,
initializes `host/compukter-vm` at the revision pinned by that checkout, prepares an independent Gradle cache through
the [sandbox cache workflow](verification.md#gradle-caches-in-agent-worktrees), and prints the command to enter it.
The original checkout can contain unrelated edits; they are preserved. Invoking the command from another parent
worktree still places the new checkout under the main workspace. It does not fetch, pull or switch the base branch.

Choose additional modules or create a task in an already initialized submodule repository:

```sh
./worktree-task feat/create-support --submodule addons/create
./worktree-task chore/addon-integration --all-submodules
./worktree-task fix/vm-execution --repo host/compukter-vm
./worktree-task fix/create-addon --repo addons/create
```

Submodule tasks start from local `main` and use names such as `.worktrees/host-compukter-vm--fix-vm-execution`.
Use `--base <local-branch>` or `--path <directory>` to override those defaults, and `--no-submodules` for work that
needs none. Extra `--submodule` options can be repeated. Modules are always initialized at the new checkout's pinned
revisions, including recursive dependencies; the source checkout's current module HEAD is not copied. Existing local
module Git objects are reused when they contain the pinned revision, without changing stored remote URLs. Missing
objects or nested modules can require network access. If GitHub SSH has no usable agent, an installed `gh` supplies
HTTPS authentication through command-scoped settings. Agents must run potential remote operations outside the sandbox.

Setup refuses existing destinations and branches. If submodule or cache preparation fails after checkout creation,
it preserves the branch and checkout and prints the location. Correct the problem and repeat the same command with
`--resume`; this requires a clean registered checkout on the expected task branch and preserves its current HEAD.
After making edits, finish any remaining setup manually rather than using resume. After verified integration into
`dev` (or submodule `main`), retain verification logs in the main checkout and remove only that completed task's
worktree. New tasks start from the updated base.

### Diagnose readiness

```sh
./worktree-task doctor
./worktree-task doctor --path .worktrees/feat-display-api
```

Diagnostics print `OK`, `INFO`, `WARN` or `ERROR` with the checkout/branch, committed submodule pins,
Java launcher (JDK 25 for Gradle), required commands, cache home/template and an actual reflink probe
between the template filesystem and checkout. An absent or cold cache is informational: the first build
may need network access. A populated cache does not prove that every offline task can run. Missing initialization of selected submodules (VM by default), pin mismatches, an invalid Java launcher, shared/symlinked cache homes or unsupported
reflinks produce a nonzero exit code. Unselected submodules and dirty files are reported without
repairing them. Submodule-only Rust checkouts skip Gradle/JDK/cache checks. The command does not fetch,
install dependencies or start a build; its temporary reflink probe files are removed.

### Complete a task

Commit, merge and verify the integrated change first, then run from outside the checkout being removed:

```sh
./worktree-task finish feat/display-api
./worktree-task finish fix/vm-execution --repo host/compukter-vm --evidence /path/to/cargo-test.log
```

`finish` uses the task record written during setup, checks that the recorded branch still owns that
worktree, and requires its HEAD to be an ancestor of the recorded local base (`dev` or submodule `main`
by default). It performs no merge, commit, push or branch deletion. Dirty or untracked files, locked
worktrees, active managed Gradle builds, mismatched/dirty submodules, unpublished submodule branch/tag
commits and additional worktrees belonging to nested module clones prevent removal.

Provide real verification output through repeatable `--evidence <file-or-directory>` options, or keep it
in the task's `build/agent-logs/`, which is included automatically. Evidence must exist and directories
must contain files; this records agent-supplied evidence, without interpreting it as proof that tests
passed. Before removal, the command archives it with task/integration commits and ignored files other
than disposable build outputs and caches under the main workspace's
`build/agent-logs/worktree-completion/<task>-<unique-id>/`. Dev worlds, temporary plans and IDE settings
are preserved there. Generated `build`, `.gradle`, `.kotlin`, root `target`, `.gradle-sandbox` and
`.toolchain` data are disposable. If archiving fails, the checkout remains intact.

Git requires `--force` to remove worktrees containing submodules even after deinitialization. The helper
uses it only for those checkouts after all cleanliness, pin, local-ref and preservation checks; it never
force-removes a dirty checkout. Other worktrees are untouched. `--path` optionally asserts the recorded
checkout location. For a task created with an older helper, explicitly register its clean checkout by
repeating its setup command with `--resume` before using `finish`. A new task always starts from the
updated local base.

## Minecraft development

Run Gradle with JDK 25 selected through `JAVA_HOME` or a Gradle-discoverable
installation:

```bash
./gradlew-sandbox-dev-parallel :v26_1-neoforge:runClient
./gradlew-sandbox-dev-parallel :v26_1-neoforge:runGameTestServer
./gradlew-sandbox-dev-parallel :v26_1-neoforge:buildProductionUniversalJar
./gradlew-sandbox-dev-parallel :v1_21_1-neoforge:runClient
./gradlew-sandbox-dev-parallel :v1_21_1-neoforge:buildProductionUniversalJar
```

To collect the production mod JARs and first-party addons in one workspace folder, run:

```bash
./gradlew-sandbox-dev-parallel-summary -p addons/dev collectDistributionJars
# Clean exact-tag universal release using the pinned downloadable Runtime bundles:
./gradlew-sandbox-dev-parallel-summary -p addons/dev collectReleaseDistributionJars
```

The task builds and verifies the archives before synchronizing `dist/`: `1.21.1/` contains Compukters plus the
Create, Sable and Propulsion addons; `26.1.2/` contains Compukters. Re-running removes stale distribution files.
`collectDistributionJars` builds Rust locally and packages the configured local native platform.
`collectReleaseDistributionJars` downloads the pinned Linux/Windows Runtime bundles and runs both clean tagged
release gates before collecting the same mod/addon layout. Full candidate verification remains a separate step.
Third-party mods required by addons must be installed separately.

Minecraft-independent Gradle modules are grouped beneath `modules/common`, while
all game-facing code is grouped beneath `modules/minecraft`. Minecraft and
NeoForge code shared across supported versions has one canonical source tree
under `modules/minecraft/shared`. IntelliJ indexes that tree against
the target selected by `compuktersActiveMinecraftVersion` in `gradle.properties`;
change the property to `1.21.1` or `26.1.2` and reload the Gradle project when
switching the version being edited. Inactive targets use generated mirrors, so
ordinary Gradle verification continues to compile both versions from the same
canonical content.

For fast feedback, `./gradlew-sandbox-dev-parallel-summary verifyLocalFast` runs policy,
build-script, and a curated JVM test slice. Before treating the current checkout
as fully verified, run `./gradlew-sandbox-dev-parallel-summary verifyLocalFull`; it covers
every Gradle subproject check, all registered Kotlin-to-VM conformance scenarios, Rust, FFM and JNI checks, runtime
integrations, the real GameTest server, and the
production artifacts for the locally configured native platform.

A distributable multi-platform release has a stricter, separate gate:
running `buildReleaseUniversalJar` from the repository root selects both
version-specific tasks. It requires a clean exact-tag checkout and the pinned
Linux and Windows Runtime bundles, then assembles and verifies both the 26.1.2
FFM and 1.21.1 JNI artifacts. A successful local full verification does not by
itself establish release readiness.

See [Publish Compukters releases](releases.md) for the GitHub/Modrinth workflow,
credentials, candidate validation, and publication recovery.

## Standalone playground

The playground exercises the same isolated compiler, artifact verifier, FFM
adapter, Rust VM, and terminal capability that the mod will use. Run the
included multi-file example from the repository root:

```bash
./gradlew :playground:run --args examples/hello
```

It prompts on stdout, reads one UTF-8 line from stdin, and executes the emitted
Compukter bytecode. To retain the verified compiler output for inspection:

```bash
./gradlew :playground:run --args="examples/hello --emit build/hello.cpkt"
```

Compilation diagnostics and runtime failures go to stderr. Add `--debug` to
show launcher stack traces. The process uses stable exit categories: `2` usage,
`3` project input, `4` compilation, `5` compiler platform, `6` artifact
verification, `7` VM admission/start, `8` guest trap, `9` VM fault, `10` host
failure or EOF, `11` quota, `12` allocation resource failure, and `13` launcher
or native platform failure.


For combined addon and physics runs, follow the [all-addon development stand]({{ '/DEV-STAND/' | relative_url }}).
