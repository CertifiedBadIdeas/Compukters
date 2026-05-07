# CKVM Image Bundled Resource Audit Design

## Goal

Add the next runtime-parity checkpoint after operators, function calls, records, fields, and collections: a focused compile-to-image audit for bundled CKL resources.

The audit should answer one question clearly: can every bundled firmware and ROM `.ck` program lower to `CkVmImage` without hitting unsupported image backend instructions or frontend diagnostics?

## Scope

Included:

- Add a dedicated test class for image parity resource compilation.
- Load `firmware/bios.ck` from the test classpath.
- Load `rom/rom.index` from the test classpath and then load every indexed `rom/*.ck` resource.
- Compile every resource with `LanguageFrontend(LanguageBuiltins.defaultRuntimeRegistry).compileImage(...)`.
- Use a classpath-backed source loader so ROM imports resolve against the same bundled source set.
- Fail with an aggregated diagnostic report listing every resource that cannot compile to image.

Excluded:

- Do not execute the compiled images through the native runner in this slice.
- Do not implement class/object opcodes in this slice.
- Do not change CKL resource source code unless the audit exposes a resource bug that blocks compilation independently of image lowering.
- Do not replace existing ROM syntax/content tests.

## Test Location

Create a new test file in the NeoForge implementation test source set:

`modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/CkVmImageBundledResourceCompileTest.kt`

This keeps bundled resource parity close to the existing `RomScriptCompileTest` resource-loading patterns while separating image-lowering parity from source-level ROM policy tests.

## Resource Loading

The test should use `CkVmImageBundledResourceCompileTest::class.java.classLoader`.

Resource paths:

- `firmware/bios.ck`
- `rom/rom.index`
- each non-blank, non-comment entry from `rom/rom.index`, loaded as `rom/<entry>`

The source loader passed to `compileImage` should resolve imports by path. For an import path that exists in the loaded source map, it should return that source text. If a source is missing, return `null` and let the frontend produce its normal unresolved-source diagnostic.

## Diagnostic Reporting

The audit should compile all resources before failing, instead of failing on the first broken resource.

For each resource, collect:

- resource path;
- `artifact.errorMessage` when present;
- frontend diagnostic messages with source ranges when available.

The failure message should start with a stable summary such as:

`Bundled CKL resources failed to compile to CkVmImage:`

Then append one section per failing resource.

This makes the test useful as a blocker finder for the next parity slice.

## Expected Initial Outcome

This is a RED-first audit. If all bundled resources already compile to image, the test will pass and document that the next blocker is runtime execution rather than image lowering. If the test fails, the first failing unsupported instruction should drive the next implementation slice.

Based on the current backend unsupported test, the likely next blocker is class/object support (`ConstructClass`, and then `SetField`, `CallMethod`, or `CallStaticMethod`), but the audit should not assume that result.

## Acceptance Criteria

- The new test compiles all bundled firmware and ROM `.ck` resources through `compileImage`.
- The test uses bundled classpath resources rather than filesystem paths.
- Missing resources produce clear failures.
- Image-lowering failures are aggregated and actionable.
- The test is committed separately from any later opcode implementation.
