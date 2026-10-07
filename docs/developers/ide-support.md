---
layout: default
title: IDE and compiler tooling support
section: developers
permalink: /IDE-SUPPORT/
---

# IDE and compiler tooling support

[← Guest Kotlin support]({{ '/KOTLIN-SUPPORT/' | relative_url }})

* On this page
{:toc}

This page describes the current checkout, including unreleased work. [Status and evidence policy]({{ '/KOTLIN-SUPPORT/#status-and-evidence' | relative_url }}) applies to every entry.

## Compiler diagnostics

### Compiler diagnostic source coordinates

**Status:** Supported.

Syntax and type diagnostics preserve virtual paths and UTF-16 offsets while bounding count and text.

**Evidence:**
[`K2CompilerAdapterTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/compiler-k2/src/test/kotlin/ru/lazyhat/compukters/compiler/worker/k2/K2CompilerAdapterTest.kt),
tests `syntax and type diagnostics use virtual paths and UTF-16 offsets` and `diagnostic count text and
physical paths are bounded`.

## IDE and tooling

### Local projects and Git

**Status:** Implemented; in-client visual observation pending for [#703](https://github.com/CertifiedBadIdeas/Compukters/issues/703).

Create, clone HTTPS repositories, open existing project directories in place and switch projects. The Git panel
provides status, colored HEAD-to-working preview, checkbox-selected commits and editable commit/author fields,
Changes/Log tabs, branch/repository menus, fetch, fast-forward-only pull and push. Private repository credentials are
masked and session-only. Save barriers and generation checks protect open
buffers; tree/analysis refresh follows working-tree updates. File/folder rename rebases open descendant paths.
`.git` is excluded from project content and compiler snapshots. SSH, merge, submodules, LFS and linked worktrees are deferred.

**Evidence:** `JGitBackendTest`, `GitWorkspaceIntegrationTest`, `ProjectCatalogTest`, `ProjectSnapshotTest`,
`IdeClientControllerTest`, `IdePromptTest`, `IdeInputAdapterTest`, `IdeRendererStateTest`, and both production archive
gates for relocated Git classes/resources. Automated Git transport tests use controlled local remotes; real HTTPS
hosting/authentication and visible interaction remain part of the manual scenario. See the
[player guide]({{ '/IDE/' | relative_url }}) and [owning contracts](../contributors/architecture/tooling.md#client-local-projects-and-git).

### Incremental lexical highlighting

**Status:** Supported.

Edits propagate lexical state and remain identical to a full scan.

**Evidence:**
[`IncrementalKotlinHighlighterTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-core/src/test/kotlin/ru/lazyhat/compukters/ide/highlight/IncrementalKotlinHighlighterTest.kt),
tests `edits propagate lexical state and remain identical to a full scan` and `seeded random edits always
equal the full-scan oracle`.

### Smart Kotlin delimiter and block entry

**Status:** Supported.

Writable Kotlin sources insert and track balanced delimiters, wrap selections, remove untouched pairs with
Backspace, and preserve structural indentation and line endings on Enter without applying the behavior to
plain-text files.

**Evidence:**
[`KotlinSmartTypingTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-core/src/test/kotlin/ru/lazyhat/compukters/ide/editor/KotlinSmartTypingTest.kt),
tests `pairs wrap and tracked closers remain distinct from ordinary source`, `paired backspace and undo are
atomic`, `pairing is suppressed inside strings and comments`, and `structural enter preserves CRLF and splits
an automatic brace pair`, plus
[`IdeClientControllerTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/controller/IdeClientControllerTest.kt),
test `Kotlin smart typing flows through writable editor while plain text stays literal`.

### On-demand Kotlin formatting

**Status:** Supported.

Ctrl+Alt+L and the toolbar Format action format writable `.kt` sources with ktlint standard rules, applying
the result as one undoable edit with a mapped UTF-16 caret and leaving it dirty for a separate save. Stale
results never replace newer typing; formatter failures warn without saving, while Ctrl+S, autosave, implicit
saves, previews, and non-Kotlin files remain unaffected.

**Evidence:**
[`KotlinFormatterTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-kotlin-formatter/src/test/kotlin/ru/lazyhat/compukters/ide/formatter/KotlinFormatterTest.kt),
[`RelocatedKotlinFormatterTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-kotlin-formatter/src/test/kotlin/ru/lazyhat/compukters/ide/formatter/RelocatedKotlinFormatterTest.kt),
[`IsolatedKotlinFormatterTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/formatter/IsolatedKotlinFormatterTest.kt),
[`FormatQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/FormatQueryTest.kt),
and
[`IdeAnalysisFlowTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/controller/IdeAnalysisFlowTest.kt),
tests `explicit format changes Kotlin atomically and leaves saving separate`, `stale format result never
overwrites or saves newer typing`, `format failure warns without saving the unformatted Kotlin source`, and
`explicit save bypasses the asynchronous formatter`.

### Semantic highlighting and inferred-type presentation

**Status:** Supported.

Declarations, extension functions, inferred expressions, and smart casts receive K2-backed semantic tokens;
mutable properties, locals, and their resolved references carry the Islands Dark underline effect.

**Evidence:**
[`SemanticTokenQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/SemanticTokenQueryTest.kt),
tests `presentation classifies declarations and extension functions`, `presentation marks inferred and smart
cast expressions`, and `presentation marks mutable declarations and references`, plus
[`IdeRendererStateTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/minecraft/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/ide/IdeRendererStateTest.kt),
test `expression metadata does not override lexical code colors`.

### K2 diagnostics

**Status:** Supported.

Incomplete syntax remains analyzable, multi-file diagnostics retain virtual paths, and UTF-16 ranges remain
exact.

**Evidence:**
[`DiagnosticQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/DiagnosticQueryTest.kt),
tests `type error after supplementary character keeps UTF-16 range`, `diagnostics from multiple files retain
their virtual paths`, and `incomplete syntax produces a bounded diagnostic instead of failing analysis`.

### Semantic completion with overloads

**Status:** Supported.

Completion uses inferred receivers, applicable extensions, visibility, distinct overload entries, argument
labels, deterministic ranking, and bounded result counts. Identifier matching supports case-insensitive
contiguous CamelCase word fragments for scoped declarations, members, libraries and autoimports, without typo
correction or arbitrary skipped letters inside words. Exact names and direct prefixes rank before CamelCase
matches. Global lookup retains its prefix fast path and supplements it through a character index with bounded
best-match selection. Identifier name fragments matching the query are highlighted using worker-supplied
Unicode-safe ranges, including trimmed fragments and autoimports; parameter and return-type columns are not
matched. Function labels use braces for a single non-null, non-vararg lambda argument, displaying its name and
expanded function type without lambda-internal parameter names. The same presentation applies to resolved
autoimport candidates. Function parameters and result types retain type-scope specialization and K2 extension
receiver inference, while independent presentation records declared receiver notation and package context.
Contextual query-only expression fragments preserve receiver inference for literals, call chains and empty
safe-call selectors, without mutating the admitted snapshot. Unknown generic arguments, including a scope
function's independent lambda-result type, remain symbolic. Function completion inserts parentheses or a final
lambda block from resolved parameter types, keeping the caret inside required arguments or the lambda body.
Existing delimiters are reused; autoimports and caret placement share an atomic undo/redo entry. Enter expands
an empty lambda into an indented block while preserving LF/CRLF and leading indentation. Imports, callable
references, type positions and shorthand string interpolation still insert only names. Automatic completion is
suppressed at function declaration names, including extension and local functions, without suppressing type or
body completion or explicit requests.

**Evidence:**
[`CompletionQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/CompletionQueryTest.kt),
tests `qualified completion uses inferred receiver members and applicable extensions`, `completion preserves
overloads and orders them deterministically`, and `completion gives standard library overloads distinct
argument labels`, and `completion tolerates synthetic function interfaces from platform libraries`, plus
[`CompletionIntegrationTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/integration/CompletionIntegrationTest.kt),
test `forked worker returns semantic completion`; `CompletionQueryTest`, tests `completion derives call shapes
from resolved function parameters`, `autoimported project functions retain semantic call shapes`, and `block
interpolation permits calls but an empty shorthand template does not`, `automatic completion suppresses
function declaration names`, and `function declarations retain completion in types bodies and explicit
requests`; `CompletionQueryTest`, tests `completion labels single lambda arguments with braces and unnamed
function types` and `autoimported lambda function labels use the same brace presentation`;
`CompletionQueryTest`, test `completion preserves specialized member and extension signatures with declared
receiver context` and `scope functions specialize their result and lambda from the explicit receiver`
(including an empty dot selector, nullable receivers, generic receiver expressions and empty safe-call
completion), `literal and chained receivers specialize extensions without inferring independent lambda
results`, and `completion fragment keeps lexical receivers shadowing and snapshot state`;
`CompletionNameMatcherTest`, tests `camel matching consumes contiguous fragments of name words` and `match
quality favors exact then direct then case insensitive then camel`; `CompletionNameMatcherTest`, test `matched
ranges follow the same deterministic contiguous unicode match`; `CompletionQueryTest`, tests `camel completion
matches word prefixes but not arbitrary skipped letters`, `completion allows trimmed word fragments without
gaps inside a word`, `camel completion also finds admitted stdlib declarations`, and `camel completion covers
members locals and autoimports with direct prefixes first`; `GlobalCompletionIndexTest`, test `bounded lookup
ranks direct prefixes before case insensitive and camel matches`; `DiagnosticQueryTest`, test `semantic-only
presentation omits diagnostics and retains symbol highlighting`;
[`IdeCompletionInsertionTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/analysis/IdeCompletionInsertionTest.kt),
tests `call completion with import preserves the caret through one undo and redo` and `completed lambda enters
an indented block and generated closers are skipped`;
[`KotlinSmartTypingTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-core/src/test/kotlin/ru/lazyhat/compukters/ide/editor/KotlinSmartTypingTest.kt),
test `enter splits existing spaced lambda braces and preserves endings indentation and caret history`.

**Related work:** [#673](https://github.com/CertifiedBadIdeas/Compukters/issues/673).

### Context-aware keyword completion

**Status:** Supported.

Declaration, modifier, statement, and expression keywords are ranked with semantic symbols for valid file,
class-body, and executable-block contexts, while imports, package directives, qualified access, comments, and
literal string content suppress them.

**Evidence:**
[`CompletionQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/CompletionQueryTest.kt),
tests `completion proposes keywords for declarations and executable blocks` and `completion suppresses
keywords outside unqualified Kotlin code`.

### Expression information and callable signatures

**Status:** Supported.

Hover-style queries render inferred local types, resolved signatures, and smart-cast types. Hover also renders
bounded plain-text KDoc from the exact resolved project declaration or its attached platform source, including
declaration-name hovers. Documentation wraps within the editor and is truncated with an ellipsis when the
bounded popup height is exhausted; it does not execute HTML, fetch external content or synthesize inherited
documentation.

**Evidence:**
[`ExpressionInfoQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/ExpressionInfoQueryTest.kt),
tests `expression query renders an inferred local type`, `expression query renders a resolved callable
signature`, and `expression query reports a smart cast type`, `hover documentation follows resolved overload
and declaration names`, and `hover documentation comes from exact attached platform source`;
`AnalysisModelsTest`, tests `completion match ranges own immutable ordered unicode-safe label slices` and
`hover documentation respects utf8 detail budget`; `AnalysisProtocolHostileInputTest`, tests `completion
decoder rejects malformed unicode match ranges and excessive range counts` and `hover decoder bounds
documentation independently`; `CompletionIntegrationTest`, test `forked worker returns semantic completion`;
`IdeRendererStateTest`, tests `completion highlights exact name fragments and preserves unicode positions and
result alignment` and `hover documentation wraps unicode paragraphs and bounds long content`.

**Related work:** [#675](https://github.com/CertifiedBadIdeas/Compukters/issues/675).

### Parameter information

**Status:** Supported.

Ctrl+P opens a caret-anchored popup for the innermost call, lists bounded and deterministic K2-resolved
overload signatures, and highlights the active positional, named, or vararg parameter. The popup follows edits
and caret movement, rejects stale snapshot results, and closes on Escape, focus loss, file changes, or when
the caret leaves a call.

**Evidence:**
[`ParameterInfoQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/ParameterInfoQueryTest.kt),
[`AnalysisRequestCoordinatorTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-client/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/controller/AnalysisRequestCoordinatorTest.kt),
[`IdeAnalysisFlowTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/controller/IdeAnalysisFlowTest.kt),
[`IdeInputAdapterTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/minecraft/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/ide/IdeInputAdapterTest.kt),
and
[`IdeRendererStateTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/minecraft/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/ide/IdeRendererStateTest.kt).

### Navigation and project references

**Status:** Supported.

Declarations, selected platform APIs, builtins such as `intArrayOf`, and exact project references resolve to
their attached sources without matching unrelated same-spelling symbols.

**Evidence:**
[`NavigationAndReferencesTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/integration/NavigationAndReferencesTest.kt),
tests `forked worker navigates and finds exact project references including caret occurrences` and `forked
worker navigates to attached builtin source`, paired with
[`DeclarationQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/DeclarationQueryTest.kt),
test `navigation maps int array factory to its platform source`, and
[`ReferenceQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/ReferenceQueryTest.kt),
test `references cross project files and exclude unrelated same spelling symbols`.

### Caret symbol occurrences

**Status:** Supported.

Placing the text caret on an analyzed project identifier highlights its declaration and current-file
references, distinguishing overloads and unrelated same-named variables and methods. Interpolated references
participate, while ordinary literal text does not; stale caret and editor results are discarded. Queries start
without hover delay; mouse movement and scrolling do not reset the matches. Text edits suppress symbol
occurrences until explicit cursor placement or keyboard navigation; background analysis and reloads do not
re-enable them. Text entry without a content change also hides the matches. Selection matching remains
textual, from two Unicode characters inside string text and three elsewhere.

**Evidence:** `ReferenceQueryTest`, test `references distinguish overloads locals methods and string
interpolation`; `IdeAnalysisCoordinatorTest`, tests `caret occurrences include declaration and current file
references without waiting for hover` and `late occurrence results never highlight a different caret token or
edited document` and `typing suppresses caret occurrences across fresh analysis and reload until explicit
caret movement`; `IdeSelectionOccurrencesTest` and `IdeClientControllerTest`.

### Find Usages and method counters

**Status:** Supported.

`Alt+F7` opens bounded semantic usage results with file/line context and navigation. Same-line method labels
count resolved project references in a single presentation traversal, distinguish overloads and unrelated
methods, and open the same result panel when clicked. Zero counts are hidden. Untouched name anchors retain
rebased last-known counts while analysis is pending; fresh results replace them, and edits touching a name
discard its count. Usage results still invalidate on edits.

**Evidence:** `SemanticTokenQueryTest`, test `method usage counts distinguish overloads members and local
functions across project files`; `NavigationAndReferencesTest`, `IdeAnalysisCoordinatorTest`,
`IdeClientControllerTest`, and `IdeRendererStateTest`.

### Semantic Rename

**Status:** Partial.

`Shift+F6` admits exact writable project declarations and references, speculatively checks diagnostics and
binding preservation, then updates bounded document buffers as one project-wide undo/redo group. Unopened
files are loaded and unsaved sources participate in analysis; source, revision, disk and manifest/lock checks
reject stale plans. Saving uses the existing autosave/conflict workflow. Library symbols, `main`, inheritance
and operator/infix declarations are rejected, as are projects with analysis errors.

**Evidence:** `RenameQueryTest`, `NavigationAndReferencesTest`, `AnalysisWorkerControllerTest`,
`ProjectEditHistoryTest`, and `IdeClientControllerTest`.

**Related work:** [#672](https://github.com/CertifiedBadIdeas/Compukters/issues/672).

### Local project build and cache

**Status:** Supported.

The client builds real project snapshots, reuses the global compiler cache, deduplicates active work, and
keeps compiler I/O off the caller thread.

**Evidence:**
[`LocalIdeWorkflowTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/integration/LocalIdeWorkflowTest.kt),
test `real project resolves builds and reuses global compiler cache`, and
[`ClientCompilationServiceTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-core/src/test/kotlin/ru/lazyhat/compukters/ide/compiler/ClientCompilationServiceTest.kt),
tests `deduplicates active build and admits one distinct queued build` and `cache hit avoids another worker
request and all IO stays on service thread`.

### Target verification, deployment, and run

**Status:** Supported.

Verification is non-mutating, successful tickets can be reused by deployment, and Run saves, builds, deploys
the manifest program, then submits its installed path.

**Evidence:**
[`IdeTargetCoordinatorTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/target/IdeTargetCoordinatorTest.kt),
tests `verify is non mutating and its matching ticket is reused by deploy` and `run deploys then submits
exactly the installed path`, plus
[`IdeTargetFlowTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/controller/IdeTargetFlowTest.kt),
test `run saves builds deploys manifest program and submits canonical line`.

### Debugger and runtime inspection

**Status:** Unsupported.

There are no breakpoints, stepping, watches, stack inspection, or live variable views.

**Related work:** not scheduled
