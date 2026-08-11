# Repository Guidelines

## Project Structure & Module Organization

Compukter Kraft is a Gradle multi-module Kotlin project with native Rust VM components. Kotlin modules live under
`modules/`: `core` contains shared device/runtime logic, `native-runtime` contains architecture-neutral runtime models, and
`v1_21_1/v1_21_1-common` plus `v1_21_1/v1_21_1-neoforge` contain the Minecraft 1.21.1 integration. Host-side Rust
VM code lives in `host/compukter-vm`. Documentation is in
`docs/`, active machine ABI references are in `docs/abi`, mod metadata is in `config/`, and visual/model assets are in
`models/` and top-level logo files.

## Build, Test, and Development Commands

- AI agents running Gradle from the sandbox should use `./gradlew-sandbox` instead of `./gradlew`. It keeps
  `GRADLE_USER_HOME` in `.gradle-sandbox`, disables the Gradle daemon, and avoids sharing host Gradle lock files.
- For normal source-built development runs, prefer `./gradlew-sandbox-dev-parallel <tasks>`. It delegates to
  `./gradlew-sandbox-dev --parallel <tasks> -PcompukterVmBuildJobs=$(nproc)`.
- `./gradlew-sandbox-dev-parallel verifyLocalFast` is the default local verification entrypoint for build-script tests
  and JVM tests across Gradle modules.
- `./gradlew-sandbox-dev-parallel verifyLocalFull` runs `verifyLocalFast` and host Rust crate tests.
- `./gradlew build` builds all Gradle modules and runs standard checks.
- `./gradlew test` runs JVM unit tests across Kotlin modules.
- `./gradlew :core:test` or `./gradlew :native-runtime:test` runs focused module tests.
- `./gradlew :v1_21_1-neoforge:runClient` launches the NeoForge dev client.
- `./gradlew :v1_21_1-neoforge:buildProductionUniversalJar` builds the production remapped mod jar.
- `cargo test --manifest-path host/compukter-vm/Cargo.toml --locked --offline` runs the RV32 host VM tests.

## Coding Style & Naming Conventions

Kotlin targets JVM 17 and uses the `org.jmailen.kotlinter` plugin. Keep Kotlin package paths under
`ru.lazyhat.compukterkraft`, use four-space indentation, `PascalCase` for types, `camelCase` for members, and `*Test.kt`
for tests. Existing source files carry a GPL header; preserve it when editing or adding comparable Kotlin/Gradle files.
Rust crates use edition 2021 conventions: `snake_case` modules/functions, `PascalCase` types, and integration tests in
`tests/*.rs`.

## Testing Guidelines

Kotlin tests use `kotlin.test` on JUnit Platform; build-script tests use JUnit Jupiter. Place tests beside the owning
module in `src/test/kotlin`. NeoForge game tests live under `src/gameTest/kotlin` and are compiled by `check`; run
`:v1_21_1-neoforge:runGameTestServer` when validating in-game behavior.

## Commit & Pull Request Guidelines

Recent history uses concise Conventional Commit-style messages such as `chore(vm): ...`, `ci(roadmap): ...`, and
`docs(roadmap): ...`. Keep commits scoped and imperative. Pull requests should explain the behavior change, list
verification commands, link related issues or roadmap items, and include screenshots or short recordings for visible
Minecraft/UI changes.

## Agent-Specific Instructions

Respect user changes in the worktree. Prefer focused edits, run the narrowest useful verification, and update docs or
active machine ABI references when behavior changes.
For GitHub operations in this repository, do not use the GitHub MCP server or GitHub app connector tools. Use the
`gh` CLI instead, including `gh api` for REST or GraphQL operations that are not covered by first-class `gh` commands.
