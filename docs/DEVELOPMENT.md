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

Minecraft-independent Gradle modules are grouped beneath `modules/common`, while
all game-facing code is grouped beneath `modules/minecraft`. Minecraft and
NeoForge code shared across supported versions has one canonical source tree
under `modules/minecraft/shared`. IntelliJ indexes that tree against
the target selected by `compuktersActiveMinecraftVersion` in `gradle.properties`;
change the property to `1.21.1` or `26.1.2` and reload the Gradle project when
switching the version being edited. Inactive targets use generated mirrors, so
ordinary Gradle verification continues to compile both versions from the same
canonical content.

For fast feedback, `./gradlew-sandbox-dev-parallel verifyLocalFast` runs policy,
build-script, and a curated JVM test slice. Before treating the current checkout
as fully verified, run `./gradlew-sandbox-dev-parallel verifyLocalFull`; it covers
every Gradle subproject check, all registered Kotlin-to-VM conformance scenarios, Rust and FFM checks, runtime
integrations, the real GameTest server, and the
production artifacts for the locally configured native platform.

A distributable multi-platform release has a stricter, separate gate:
running `buildReleaseUniversalJar` from the repository root selects both
version-specific tasks. It requires a clean exact-tag checkout and the pinned
Linux and Windows Runtime bundles, then assembles and verifies both the 26.1.2
FFM and 1.21.1 JNI artifacts. A successful local full verification does not by
itself establish release readiness.

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
