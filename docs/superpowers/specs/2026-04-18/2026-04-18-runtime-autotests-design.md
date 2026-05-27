# Runtime Autotests Design

## Goal

Introduce a practical runtime autotest strategy for the mod that catches regressions in program execution, device integration, and world-facing computer behavior without turning every verification step into a full Minecraft launch.

The design should provide:

- fast headless coverage for VM and language-runtime behavior;
- focused platform-aware tests for Minecraft-dependent adapters and serialization;
- a small NeoForge GameTest layer for real world, tick, and block integration;
- a clear Gradle and CI split between cheap tests and expensive runtime integration tests.

## Scope

Included:

- test pyramid for runtime-related behavior;
- shared runtime test fixtures for workspace, profiles, and fake devices;
- NeoForge GameTest coverage for computer block integration;
- Gradle execution strategy for local runs and CI;
- first iteration test scenarios for runtime boot and device attachment.

Excluded:

- Fabric runtime GameTest support in this iteration;
- large UI automation coverage for screens;
- broad performance benchmarking;
- replacing all existing unit or integration tests with GameTest.

## Current State

The codebase already has fast runtime-oriented JVM tests in:

- [modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt](/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/runtime-autotests/modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt)
- [modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/ComputerProgramSupportTest.kt](/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/runtime-autotests/modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/runtime/ComputerProgramSupportTest.kt)
- [modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/runtime/ComputerProgramSupportTest.kt](/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/runtime-autotests/modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/runtime/ComputerProgramSupportTest.kt)

The repository also already contains a Minecraft bootstrap helper for test code in [modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/workbench/test/TestMinecraftBootstrap.kt](/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/runtime-autotests/modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/workbench/test/TestMinecraftBootstrap.kt).

Loom launch configuration already exposes NeoForge GameTestServer support, but there is no dedicated runtime GameTest suite for the computer block lifecycle.

This means the project can already validate core runtime logic cheaply, but it does not yet verify the full path from placed block to ticking runtime in a real Minecraft world.

## Design Overview

Runtime autotests should be structured as a three-layer pyramid:

1. `Headless JVM runtime tests`
2. `Minecraft-aware platform integration tests`
3. `NeoForge GameTest world integration tests`

The lower layers should catch most regressions. The upper layer should stay deliberately small and only prove that the Minecraft integration boundary is wired correctly.

## Layer 1: Headless JVM Runtime Tests

This layer remains the main safety net.

It should cover:

- workspace loading and source discovery;
- compilation and program launch preconditions;
- VM profile limits such as ROM, RAM, and queue sizes;
- runtime module availability versus device presence;
- attach and detach event delivery;
- snapshot and restore behavior;
- runtime host bridge behavior using fake or recording hosts.

These tests must not depend on a real Minecraft server or world ticks.

When a behavior can be verified purely through runtime abstractions, it belongs here rather than in GameTest.

## Layer 2: Minecraft-Aware Platform Integration Tests

This layer is for behavior that needs Minecraft classes or registries but does not need a full GameTest world.

It should use the existing bootstrap helper to validate:

- block entity serialization and deserialization;
- registry-backed runtime adapters;
- menu or inventory contracts that depend on Minecraft types;
- resource or data interactions tied to Minecraft internals;
- platform-specific runtime wiring that can be observed without world ticking.

This layer is still expected to run under normal Gradle `test` execution.

## Layer 3: NeoForge GameTest World Integration

This layer is reserved for behavior that requires a real world, server ticks, or block placement.

The first GameTests should focus on observable integration behavior:

- placing a computer block creates the expected block entity and runtime host state;
- the runtime survives initial ticks and can boot a program sourced from test workspace data;
- connected peripherals become visible to the runtime after world-side attachment;
- persisted runtime or block state survives reload when that behavior is part of the contract.

GameTests must not become a replacement for the compiler and VM unit tests.

If a failure can be isolated below the Minecraft-world boundary, it should remain in Layer 1 or Layer 2.

## Shared Test Fixture Strategy

The runtime test suite should introduce shared fixtures for the concepts that are currently hand-built in multiple tests.

The shared fixture layer should provide:

- a reusable `ComputerProfile` factory;
- a temporary `ComputerWorkspaceHost` factory;
- helper methods for writing `.ck` files into test workspace directories;
- fake or recording device registries and runtime hosts;
- helpers for repeated runtime assertions such as compile-and-run or attach-and-observe flows.

The fixture layer should live with the runtime-focused tests rather than inside production sources.

Its purpose is to reduce duplication while keeping the tests readable and explicit about scenario setup.

## GameTest Boundaries

GameTest coverage should be intentionally narrow.

GameTest is the right tool when the assertion depends on:

- world placement;
- server ticking;
- block entity lifecycle;
- Minecraft persistence boundaries;
- real interaction between NeoForge registration and runtime startup.

GameTest is the wrong tool when the assertion is mainly about:

- compiler diagnostics;
- bytecode VM behavior;
- import validation;
- device-registry business logic that can be modeled without a world.

This boundary is essential to keep the suite maintainable and fast enough for everyday development.

## Gradle And CI Strategy

The execution model should separate cheap feedback from expensive world integration:

- regular `test` remains the default local and CI verification path;
- GameTest execution gets its own dedicated task or workflow step;
- CI should report GameTest failures separately from unit and integration test failures.

This separation avoids forcing every local edit through a full Minecraft-based runtime check while still preserving automated coverage for the real integration path.

## First Iteration

The first runtime autotest iteration should deliver one reusable fixture layer and a small set of high-value scenarios.

Recommended initial scenarios:

1. headless test proving a workspace program compiles and launches with the expected profile;
2. headless test proving runtime module availability is independent from concrete device presence;
3. headless test proving attach events make a newly connected device visible to typed APIs;
4. NeoForge GameTest proving a placed computer block reaches a stable ticking runtime state;
5. NeoForge GameTest proving a world-side peripheral attachment becomes observable by the running computer.

This set is intentionally small. The goal of the first pass is to establish the harness and boundaries, not to exhaustively test every feature.

## Risks

### Overusing GameTest

If too much logic is pushed into GameTest, the suite will become slow, brittle, and expensive to debug.

Mitigation:

- keep VM and compiler logic in headless tests;
- only promote assertions to GameTest when world state is required.

### Fixture Drift Across Modules

If each module creates its own runtime test setup separately, behavior and assumptions will diverge.

Mitigation:

- define shared fixture helpers once;
- make platform-specific additions explicit instead of copying setup code.

### False Confidence From Smoke-Only Coverage

A single boot smoke test is useful, but it does not prove peripheral integration, persistence, or attachment semantics.

Mitigation:

- treat the first GameTests as the minimum entry point;
- expand coverage by integration contract, not by random feature count.

## Success Criteria

- Most runtime regressions are caught by ordinary Gradle `test` runs.
- The repository has a clear shared fixture layer for runtime tests.
- NeoForge GameTest verifies the world-facing computer integration boundary.
- The CI pipeline can distinguish fast test failures from GameTest failures.
- Developers have a clear rule for whether a new runtime scenario belongs in headless tests, platform tests, or GameTest.