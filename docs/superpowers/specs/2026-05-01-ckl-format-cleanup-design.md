# CKL Autoformatting and Auto Cleanup Design

## Context

CKL currently has a lexer, parser, semantic analyzer, bytecode compiler, and IDE services for diagnostics, completion, hover, and definitions. It does not yet expose a formatter or cleanup API for `.ck` source files.

This design covers CKL only. It does not change Kotlin/KTS project formatting or repository-wide ktlint behavior.

## Goals

- Add a deterministic CKL document formatter.
- Add CKL cleanup operations that share the formatter pipeline.
- Preserve comments.
- Expose formatter and cleanup through the existing IDE service layer.
- Keep the MVP safe: invalid or incomplete source should not be rewritten.
- Make formatter output idempotent.

## Non-goals

- Formatting Kotlin, Gradle, Markdown, TOML, or other repository files.
- Best-effort formatting of syntactically invalid CKL.
- Semantic refactorings, renames, or code motion.
- Changing declaration, statement, or expression evaluation order.
- Full language-server protocol integration in this feature; the feature exposes backend/host APIs that UI code can call.

## User-facing behavior

### Format Document

Given a valid CKL document, Format Document returns a single full-document `TextEdit` when the canonical source differs from the input. If the source is already canonical, it returns no edits.

Canonical style:

- 4 spaces per indentation level.
- Imports are printed before top-level declarations.
- Imports are sorted and duplicate selective import groups from the same source are merged.
- Blank line between import block and declarations.
- Blank line between top-level declarations.
- Spaces around binary operators.
- A space after commas.
- Block constructs use brace style already common in CKL: `fun name(...) { ... }`, `if (...) { ... }`, `class Name(...) { ... }`.
- Constructor calls remain named call-style, for example `Vec2(x = 1, y = 2)`.
- The formatter is idempotent: formatting formatted source produces no further edits.

Format Document does not remove unused imports. Removal is reserved for Cleanup Document because it needs semantic proof.

### Cleanup Document

Cleanup uses the formatter plus import organization.

It should:

- Keep the same import sorting and merging behavior as Format Document.
- Remove unused selective import items when analysis proves they are unused.
- Remove an unused namespace alias only when analysis proves the alias is unused.
- Preserve imports when analysis is ambiguous or contains errors.

Cleanup must not change runtime semantics.

## Comments

The MVP must preserve comments.

The current lexer skips line comments and block comments. The formatter needs comment trivia in the parsed source, so the lexer/parser pipeline should collect comments separately from normal tokens.

Add a `CommentTrivia` model with:

- text,
- kind (`LINE` or `BLOCK`),
- source range.

`ParsedSource` should include comments. Parser behavior does not need to change significantly: comments are trivia, not syntax nodes.

The formatter attaches comments to nearby syntax using source positions:

- leading comments before a declaration/statement/expression are printed before that construct with the construct indentation,
- inline trailing comments remain at the end of the corresponding source line when the association is unambiguous,
- block comments keep their text and are indented consistently,
- ambiguous comments are preserved near their original relative position rather than dropped.

Exact original whitespace around comments is not preserved. It is normalized to canonical formatter whitespace.

## Invalid source behavior

If the input has lexer or parser errors, Format Document and Cleanup Document return no edits and a diagnostic/status explaining that the source cannot be formatted with syntax errors.

Examples that should return no edits:

- unterminated string literal,
- unterminated block comment,
- missing closing brace,
- partial expression such as `val x =`.

Cleanup also returns no edits when semantic analysis has error diagnostics, because unused import decisions are not reliable in that state.

## Architecture

### Formatter service

Add a compiler/frontend service, tentatively named `LanguageFormatter`.

Responsibilities:

- parse input through `ParserFacade`,
- reject invalid input,
- render AST to canonical CKL source,
- organize imports by sorting sources, sorting selective items, and merging duplicate selective groups,
- preserve comments using comment trivia,
- return `TextEdit` results instead of mutating files.

Suggested API:

```kotlin
data class FormatOptions(
    val cleanup: Boolean = false,
)

data class FormatResult(
    val edits: List<TextEdit>,
    val diagnostics: List<Diagnostic> = emptyList(),
    val changed: Boolean = edits.isNotEmpty(),
)

class LanguageFormatter(...) {
    fun formatDocument(name: String, source: String): FormatResult
    fun cleanupDocument(name: String, source: String, loader: SourceLoader = NoOpSourceLoader): FormatResult
}
```

Exact names can be adjusted during implementation to fit existing style.

### IDE facade

Extend `IdeFacade` and `LanguageIde` with formatting methods:

- `formatDocument(name, source): FormatResult`,
- `cleanupDocument(name, source, loader/sourceIndex as needed): FormatResult`.

The formatter should use the same `TextEdit` runtime model already used by completions.

### Device IDE host

Extend the device IDE API with request/response types for format and cleanup:

- `DeviceFormatRequest`, `DeviceFormatResponse`,
- `DeviceCleanupRequest`, `DeviceCleanupResponse`.

Extend `DeviceIdeHost`, `WorkspaceDeviceIdeHost`, and workbench gateway plumbing so UI code can invoke the backend formatter and cleanup commands.

### Import cleanup metadata

Unused import cleanup needs reliable import-to-symbol tracking.

The semantic analyzer should record which imported symbol comes from which `ImportItem`. Cleanup can then remove a selective item only if:

- the imported item produced a symbol,
- there are no references to that symbol outside the import declaration itself,
- semantic analysis has no error diagnostics.

If any condition is not met, cleanup keeps the import.

## Formatter rendering model

The formatter should render from AST, not by patching arbitrary token whitespace.

Suggested internal shape:

- `CklWriter` with indentation, line, blank-line, and token helpers,
- render functions for declarations, statements, expressions, type syntax, imports, and comments,
- small helpers for precedence-aware expression printing so parentheses are preserved when needed.

Rendering should cover all current CKL syntax:

- imports and aliases,
- functions and parameters,
- structs,
- classes, constructors, fields, `init`, instance methods, static methods,
- variable declarations and assignments,
- member assignments,
- `if` / `else if` / `else`,
- `while`,
- `when`,
- `return`,
- calls, named arguments, scope access, member access, `this`, literals, unary and binary expressions.

## Testing strategy

Add focused compiler module tests.

Formatter tests:

- formats messy functions,
- formats structs and class declarations,
- formats `if`, `while`, and `when`,
- formats named constructor calls,
- preserves leading, trailing, inline, and block comments,
- is idempotent.

Formatter import tests:

- sorts imports,
- sorts selective import items,
- merges duplicate selective imports,
- keeps used and unused imports because Format Document does not remove them.

Cleanup tests:

- removes unused selected items,
- preserves used function, struct, and class imports,
- preserves imports when semantic analysis has errors,
- safely handles namespace aliases.

API tests:

- `LanguageIde.formatDocument` returns the expected edit,
- `LanguageIde.cleanupDocument` returns the expected edit,
- device/workbench host methods pass through edits unchanged.

Verification commands:

- `./gradlew :compiler:test`,
- `./gradlew test`.

## Rollout notes

Implementation should proceed in small phases:

1. comment trivia support in lexer/parser pipeline,
2. pure formatter and formatter tests,
3. cleanup import metadata and cleanup tests,
4. IDE facade and device/workbench API wiring,
5. documentation and final verification.
