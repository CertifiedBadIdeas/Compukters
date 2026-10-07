---
layout: default
title: Entry points and projects
section: developers
permalink: /KOTLIN-SUPPORT/projects/
---

# Entry points and projects

[← Guest Kotlin support]({{ '/KOTLIN-SUPPORT/' | relative_url }})

* On this page
{:toc}

This page describes the current checkout, including unreleased work. [Status and evidence policy]({{ '/KOTLIN-SUPPORT/#status-and-evidence' | relative_url }}) applies to every entry.

## `main(args: Array<String>)` argument contract

**Status:** Supported.

The argument-bearing entry point receives one owned array whose strings preserve their exact UTF-16 code
units.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
test `string array entry lowers deterministically for vm argv conformance`, and
[`kotlin_writer.rs`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-artifact/src/test/rust/executable-conformance/kotlin_writer.rs),
test `k2_string_array_entry_executes_exact_utf16_arguments`.

## Two legal `main` forms

**Status:** Supported.

`fun main()` and `fun main(args: Array<String>)` lower with explicit entry tags.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
test `both legal main forms lower deterministically with an explicit entry contract`.

## Invalid entry points are rejected

**Status:** Supported.

Duplicate entries, missing entries, unsupported parameters, nullable argument arrays, and non-`Unit` results
produce no artifact.

**Evidence:**
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `entry policy rejects duplicate and invalid main functions` and `entry policy rejects a project without
main`.

## Multi-file projects

**Status:** Partial.

Cross-file top-level calls share one K2 session and lower deterministically, while the in-computer `kotlinc`
command still accepts exactly one source file.

**Evidence:**
[`K2CompilerAdapterTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/K2CompilerAdapterTest.kt),
test `cross-file reference participates in one K2 session before bounded lowering`, and
[`MinimalScriptLoweringTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/MinimalScriptLoweringTest.kt),
tests `multi-file terminal program lowers through trusted symbols` and `kotlinc command line rejects ambiguous
or unsupported arguments`.

**Related work:** not scheduled

## Project manifests and modules

**Status:** Partial.

`compukter.toml` selects a portable native platform module graph by identity, while `compukter.lock` records
exact resolved versions and hashes for the IDE, analyzer, and compiler. Compiler output is still one
application artifact rather than an independently distributable Kotlin module ecosystem.

**Related work:** not scheduled
