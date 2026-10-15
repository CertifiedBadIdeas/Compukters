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

### Kotlin typing and indentation

**Status:** Supported.

Enter preserves indentation, adds one level after a code opening brace and splits empty brace pairs. Tab/Shift+Tab
indent/outdent touched lines as atomic edits. Clipboard paste in Kotlin code removes the fragment's common visual
indentation and rebases subsequent lines to the destination prefix, preserving relative nesting and document line
endings. Single-line paste, insertion inside literals/comments, fragments containing multiline string tokens and
non-Kotlin files remain literal. Clipboard admission and editor/history limits apply before accepting the edit.

**Evidence:** `KotlinSmartTypingTest`, tests `structural enter preserves CRLF and splits an automatic brace pair`,
`enter splits existing spaced lambda braces and preserves endings indentation and caret history`,
`paste rebases common indentation and preserves nested offsets with atomic undo`,
`paste retains relative tab indentation blank lines and replaces backward selection`,
`paste in literals comments plain text or fragments containing raw strings remains exact` and
`rejected indented paste preserves source caret and history`; `EditorDocumentTest` block indentation coverage;
`IdeClientControllerTest`, tests `editor fundamentals flow through controller state as atomic edits and navigation`
and `clipboard paste uses Kotlin indentation and plain files remain literal`; `IdeInputAdapterTest`,
`paste is bounded on UTF-16 and UTF-8 boundaries`.

### Live named peripheral availability

**Status:** Implemented; in-client observation pending for [#787](https://github.com/CertifiedBadIdeas/Compukters/issues/787).

For an attached computer, resolved `named` lookups with string literals or compile-time constants receive warnings
when the current network cannot supply the device. `namedOrNull` receives informational hover/Problems entries
without warning underlines. The explanation can identify absence, incompatible types, duplicate reachable matches,
access radius, dimension, unloaded chunk or removed/replaced devices. The observation refreshes independently of
Devices and clears on detach, request failure or expiry. Dynamic names and first/side/predicate searches are not
checked. Incomplete observations do not imply absence; these diagnostics do not fail compilation.

**Evidence:** `PeripheralLookupQueryTest`, `PeripheralDiagnosticsTest`, `AnalysisProtocolRoundTripTest`,
`IdePeripheralObserverTest`, `IdeTargetFlowTest`, `IdeRendererStateTest`, `IdeTargetRequestPayloadTest`,
`IdeTargetReplyPayloadTest` and the network inspection assertions in `PeripheralCableGameTestScenario`.

### Canonical platform source admission

Navigation source loading uses the same canonical platform budget as the analysis protocol and worker:
256 Kotlin files, 1 MiB per file and 8 MiB total. These trusted base sources are independent of the project
and addon budgets. Project limits continue to bound user sources, and attached addon sources retain their
own separate pool with the configured addon limits, including across successive bundle additions.

**Evidence:** `IdeClientServicesTest`, tests `attached canonical sources use the platform budget rather than
the project budget`, `attached canonical archive still rejects too many platform files` and
`prepares distinct compiler and analysis workers from one shared bundle`; `IdeSemanticInteractionTest`,
test `larger packaged source budget preserves smaller addon limits across additions`;
`AnalysisProtocolRoundTripTest`, test `canonical platform sources have an independent bounded budget`.

### Local projects and Git

**Status:** Implemented; in-client visual observation pending for [#703](https://github.com/CertifiedBadIdeas/Compukters/issues/703).

Create, clone HTTPS repositories, open existing project directories in place and switch projects. The Git panel
provides status, colored HEAD-to-working preview, checkbox-selected commits and an editable commit draft in the left
Project area. Git settings contain origin, author and session credentials; GitHub token validity is checked in the
masked token window, independently of repository permissions. The full-width bottom Git Log switches with Problems,
refreshes local history automatically and offers explicit fetch,
fast-forward-only pull and push. A left icon stripe groups tool windows; all IDE text uses bundled JetBrains Mono.
Git colors cover the tree, active title and Changes rows, including parent folders.
Read-only background inspection supplies bounded HEAD-to-buffer gutter changes, including unsaved text,
without a save barrier; project, operation and document-revision checks reject stale results. Private repository credentials are
masked and session-only, bound to the explicitly selected HTTPS host and effective port. Transport checks cover rewritten URLs and cross-origin redirects before headers are attached. Network errors do not expose remote exception text; JVM/HTTP string copies remain outside the buffer-erasure guarantee. Save barriers and generation checks protect open
buffers; tree/analysis refresh follows working-tree updates. File/folder rename rebases open descendant paths.
`.git` is excluded from project content and compiler snapshots. SSH, merge, submodules, LFS and linked worktrees are deferred.

**Evidence:** `JGitBackendTest`, `GitCredentialSecurityTest`, `GitWorkspaceIntegrationTest`, `ProjectCatalogTest`, `ProjectSnapshotTest`,
`IdeClientControllerTest`, `IdePromptTest`, `IdeInputAdapterTest`, `IdeRendererStateTest`, and both production archive
gates for relocated Git classes/resources. Automated Git transport tests use controlled local remotes; real HTTPS
hosting/authentication and visible interaction remain part of the manual scenario. See the
[player guide]({{ '/IDE/' | relative_url }}) and [owning contracts](../contributors/architecture/tooling.md#client-local-projects-and-git).

### Incremental lexical highlighting

**Status:** Supported.

Edits propagate lexical state and remain identical to a full scan.
Lexical fallback colors function calls, qualified/use-site annotations and complete Unicode escapes.
Numeric scanning distinguishes ranges and member access from decimal, exponent, hexadecimal, binary and
unsigned literals. Available semantic highlighting takes precedence over lexical fallback.

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
Named argument labels have a distinct color, including incomplete overloaded calls; the argument
expression and ordinary parameter colors remain unchanged.

**Evidence:**
[`SemanticTokenQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/SemanticTokenQueryTest.kt),
tests `presentation classifies declarations and extension functions`, `presentation marks inferred and smart
cast expressions`, and `presentation marks mutable declarations and references`, plus
[`IdeRendererStateTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/minecraft/shared/common/src/test/kotlin/ru/lazyhat/compukters/impl/ide/IdeRendererStateTest.kt),
tests `expression metadata does not override lexical code colors` and `named argument label uses its own
color without changing the argument expression`; `SemanticTokenQueryTest` also covers `named argument
labels retain distinct highlighting in incomplete overloaded calls`.

### K2 diagnostics

**Status:** Supported.

Incomplete syntax remains analyzable, multi-file diagnostics retain virtual paths, and UTF-16 ranges remain
exact. Problems rows flatten multiline messages into printable text; diagnostic tooltips preserve
line breaks and indentation without rendering CR/LF, tabs or other control characters as glyphs.

**Evidence:**
[`DiagnosticQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/DiagnosticQueryTest.kt),
tests `type error after supplementary character keeps UTF-16 range`, `diagnostics from multiple files retain
their virtual paths`, and `incomplete syntax produces a bounded diagnostic instead of failing analysis`;
`IdeRendererStateTest`, test `multiline diagnostics render printable problem rows and preserve tooltip lines`.

### Explicit import quick fixes

**Status:** Supported; manual client appearance check pending.

`Alt+Enter` requests exact-name import candidates at the caret. The analysis protocol carries bounded,
snapshot-scoped insertion edits rather than completion edits. The worker reuses the admitted declaration
indexes and import planner, then previews at most 16 distinct imports with K2. Each accepted preview must
resolve the chosen reference, preserve existing resolved bindings, and introduce no new errors. Syntax
errors suppress the action; unrelated semantic errors may remain. Only admitted project and active library
symbols participate. PSI-to-source coordinate mapping preserves raw UTF-16 positions and LF/CRLF.

The client rejects stale snapshot, source, target and caret state. Applying an import preserves selection
and call text with one undo entry. Keyboard and mouse selection share the canonical popup components.
This action does not install dependencies or implement general intentions.

**Evidence:** `ImportFixQueryTest`, the forked-worker import test in `CompletionIntegrationTest`,
`AnalysisProtocolRoundTripTest`, `AnalysisProtocolHostileInputTest`, `EditorDocumentTest`,
`IdeAnalysisFlowTest`, `IdeInputAdapterTest` and `IdeRendererStateTest` cover resolution, bounds,
raw source coordinates, stale rejection, atomic application and popup/input ownership.

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
body completion or explicit requests. Variable, parameter and destructuring declaration names produce no
completion candidates; type annotations, initializers and existing-variable references retain semantic completion.
Within call arguments, completion ranks values and callable results by K2 assignability to the expected
parameter types. Exact types rank before compatible subtypes; nullable results, safe-call results and generic
type arguments retain Kotlin semantics. Best overload candidates supply the expected types, including named,
positional and vararg arguments; spread and nested-expression contexts use K2's local expected type. Other
matching values remain available, and contexts outside arguments retain ordinary ranking. Named-argument
labels keep their existing priority. Matching Boolean and nullable contexts also offer `true`, `false` and `null`
with an empty completion prefix, while expected enum values insert qualified names such as `Mode.FIRST`.
Autoimported properties retain their import edits while participating in type ranking. Type analysis remains
inside the K2 session; the unchanged worker protocol carries the resulting item order.

**Evidence:** `ExpectedTypeCompletionTest`, tests `positional and named arguments rank compatible values without hiding other types`,
`nullable and subtype compatibility preserve Kotlin assignability`, `generic inference and overloads rank values from applicable parameter types`,
`Boolean and nullable argument values offer literals for an empty prefix`, `expected enum values use valid qualified insertion and retain prefix matching`,
`call results and qualified properties use their substituted result types`, `vararg spread and safe call results respect argument value types`,
`autoimported property types take priority while keeping their import edits`, `SDK aliases and generic type arguments use semantic compatibility`,
`unrelated contexts and unresolved calls retain ordinary completion ordering`,
`exact expected types rank before compatible subtypes and item caps preserve matches`,
`enum suggestions insert qualified cross-package names and highlight escaped entries`,
and `short string interpolation keeps name-only completion without value literals`.

At the start of a call argument, completion also offers `parameter = ` names from K2 call candidates,
including constructors and member/extension overloads. Already bound positional, named and trailing-lambda
parameters are excluded. Candidates retain the substituted parameter type, reuse an existing `=`, and do not
appear inside argument values or nested expressions. Functions without stable parameter names do not offer
named arguments. Accepting a name leaves the caret after `= ` and uses one undoable edit.

**Evidence:** `NamedArgumentCompletionTest`, tests `named arguments complete missing names without replacing expression completion`,
`named arguments exclude positional named and trailing lambda bindings`, `named arguments complete constructor and member overload parameters`,
and `named argument labels preserve existing equals and values do not offer labels`; `IdeAnalysisFlowTest`,
test `named argument completion places caret after equals and is one undoable edit`.

Dot and identifier edits request background snapshot preparation and automatic completion without intentional
debounce delays; worker analysis and the next client frame still determine when the list appears. Literal content
in quoted and raw strings, character literals and comments produces no completion candidates, including explicit
requests. Shorthand `$name`, a bare unescaped `$` and
`${...}` interpolation retain completion; nested string literals and comments inside interpolation are excluded.

**Evidence:** `IdeAnalysisCoordinatorTest`, test `dot and identifier edits prepare completion without waiting for
the presentation debounce`; `AnalysisRequestCoordinatorTest`, test `zero-delay automatic completion dispatches
without advancing time and replaces obsolete requests`; `IdeClientServicesTest`, test `production analysis timing
favors completion without starving presentation`; `IdeVisibleLatencyPerformanceTest`, tests `production analysis
reaches the first visible frame latency targets` and `production burst keeps input responsive and analyzes its
final revision`, cover the real worker, including rapid identifier edits.
[`CompletionQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/CompletionQueryTest.kt),
tests `qualified completion uses inferred receiver members and applicable extensions`, `completion preserves
overloads and orders them deterministically`, and `completion gives standard library overloads distinct
argument labels`, and `completion tolerates synthetic function interfaces from platform libraries`, plus
[`CompletionIntegrationTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/integration/CompletionIntegrationTest.kt),
test `forked worker returns semantic completion`; `CompletionQueryTest`, tests `completion derives call shapes
from resolved function parameters`, `autoimported project functions retain semantic call shapes`, and `block
interpolation permits calls but an empty shorthand template does not`, `automatic completion suppresses
function declaration names`, and `function declarations retain completion in types bodies and explicit
requests`, `variable parameter and destructuring declaration names do not offer existing symbols`,
`variable declarations keep type initializer and existing-variable completion`, `completion suppresses literal content and comments including nested template literals`, and
`completion remains available inside quoted and raw string interpolations`; `CompletionQueryTest`, tests
`completion labels single lambda arguments with braces and unnamed function types` and
`autoimported lambda function labels use the same brace presentation`;
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

Hover-style queries render inferred local types, resolved signatures, and smart-cast types using K2 short names,
including generic, nullable and function types. The popup shows the declaration signature with its return type (including `Unit`), or the inferred type
when no declaration signature is available. KDoc subjects and descriptions share a row when space allows,
and source information follows the documentation. Long hovers and the code editor expose mouse-draggable
scrollbars with page clicks; editor scrolling preserves caret, selection and document revision. Hover also renders
bounded KDoc from the exact resolved project declaration or its attached platform source, including
declaration-name hovers. Platform overload matching uses normalized type signatures, including callback
parameters and receiver callbacks, so hover and Go to Declaration select the same attached source.
English documentation groups parameters, return values, exceptions (`@throws` and
`@exception`), receiver, properties, constructor, samples, related declarations, since and author into separate
sections. `@suppress` is shown as a generated-documentation exclusion; unfamiliar tags remain visible.
Descriptions support paragraphs, headings, lists, emphasis, inline code, displayed reference labels and fenced
or indented code examples. Long documentation wraps and scrolls inside the popup without scrolling the source;
layouts are cached per hover and width. Source KDoc highlights tags, subjects, links, inline code and fenced
Kotlin code while preserving comment editing behavior. Models separate tag identities and subjects from English
presentation labels for future localization; only English is implemented.
Reference labels and `@sample` identifiers are displayed, but clicking documentation links and automatically
embedding referenced sample bodies are not implemented. HTML execution, external content fetching, inherited
documentation synthesis and full CommonMark/Dokka rendering are not claimed.

**Evidence:**
[`ExpressionInfoQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/ExpressionInfoQueryTest.kt),
tests `expression query renders an inferred local type`, `expression query renders a resolved callable
signature`, and `expression query reports a smart cast type`, `hover documentation follows resolved overload
and declaration names`, `hover documentation comes from exact attached platform source`, and
`callback overloads resolve exact platform documentation and navigation`;
`AnalysisModelsTest`, tests `completion match ranges own immutable ordered unicode-safe label slices` and
`hover documentation respects utf8 detail budget`; `AnalysisProtocolHostileInputTest`, tests `completion
decoder rejects malformed unicode match ranges and excessive range counts` and `hover decoder bounds
documentation independently`; `CompletionIntegrationTest`, test `forked worker returns semantic completion`;
`IdeRendererStateTest`, tests `completion highlights exact name fragments and preserves unicode positions and
result alignment` and `hover documentation wraps unicode paragraphs and bounds long content`; `KDocDocumentTest`,
`IdeKDocPresentationTest`, `KotlinLineLexerTest` (documentation styles and fence state), and
`IncrementalKotlinHighlighterTest`.

**Related work:** [#675](https://github.com/CertifiedBadIdeas/Compukters/issues/675).

### Token diagnostic presentation

**Status:** Supported.

Analysis protocol 16 carries explicit diagnostic highlight kinds classified from K2 factory identities or compiler
codes. Unknown references color their complete identifier; errors and warnings underline source ranges, including
the initializer expression for a property type mismatch. Decorations require matching captured source text and
share tab/Unicode geometry with the editor. Overlapping messages are grouped on hover without capturing source clicks.

**Evidence:** `DiagnosticQueryTest`, test `unresolved identifiers carry a distinct highlight with their full token range`
and `type error after supplementary character keeps UTF-16 range`; `AnalysisProtocolRoundTripTest`, test
`diagnostic highlight round trips and rejects unknown kinds`; `IdeRendererStateTest`, test
`diagnostic tokens retain exact styles clipped waves and grouped noninteractive hover`.

### Parameter information

**Status:** Supported.

Typing `(` or `,` in code automatically opens argument information; strings and comments are excluded.
Ctrl+P also opens a call-anchored popup for the innermost call, lists bounded and deterministic K2-resolved
overload signatures, and highlights the active positional, named, or vararg parameter. The popup follows edits
and caret movement, wraps long signatures while keeping the active parameter visible, rejects stale snapshot results,
and allows Ctrl+Up/Down overload selection without source edits. Selection persists while refreshing the same call.
Typing argument identifiers still requests automatic completion, and Ctrl+Space keeps parameter hints available.
The completion list takes rendering and keyboard priority; Escape closes that list first, revealing retained hints.

The active parameter's `@param` description is resolved through the same source/attached-library lookup as hover,
transmitted under the negotiated detail byte limit, and rendered with shared English KDoc presentation. Descriptions
use at most six rows with an ellipsis when the available viewport cannot show the complete body. The popup
closes on Escape, focus loss, file changes, or when the caret leaves a call.

**Evidence:**
[`ParameterInfoQueryTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-k2/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/k2/query/ParameterInfoQueryTest.kt),
[`AnalysisRequestCoordinatorTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-analysis-client/src/test/kotlin/ru/lazyhat/compukters/ide/analysis/controller/AnalysisRequestCoordinatorTest.kt),
[`IdeAnalysisFlowTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/common/ide-client/src/test/kotlin/ru/lazyhat/compukters/ide/client/controller/IdeAnalysisFlowTest.kt),
[`IdeInputAdapterTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/minecraft/shared/common/src/test/kotlin/ru/lazyhat/compukters/impl/ide/IdeInputAdapterTest.kt),
and
[`IdeRendererStateTest`](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/modules/minecraft/shared/common/src/test/kotlin/ru/lazyhat/compukters/impl/ide/IdeRendererStateTest.kt).

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
and operator declarations are rejected. Infix calls, ordinary calls and callable references participate
in rename. Existing semantic errors may remain when their messages and shifted source ranges are preserved;
new errors or changed reference bindings reject the whole operation. Syntax errors and errors without
source ranges remain unsupported because complete reference validation cannot be guaranteed.

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
