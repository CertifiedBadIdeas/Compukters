# Rust-like Selective Imports and Auto Suggestions Design

## Context

CKL currently has ambient built-in namespaces such as `terminal`, accessed with `::`, and user-file imports that can be either flat (`import "math.ck";`) or namespaced (`import "math.ck" as math;`). This design removes flat imports and adds explicit selective imports plus IDE auto-import suggestions.

The goal is to support short unqualified calls like `println("hi")` without making every exported name visible by default. A name becomes unqualified only when it is listed explicitly in an import group.

## Goals

- Keep `terminal::println("hi")` valid without any import.
- Add `import terminal { println };` so `println("hi")` is valid in that file.
- Add `import "lib/math.ck" { add, Vec2 };` so only listed file exports become visible.
- Keep `import "lib/math.ck" as math;` for namespace-style access via `math::name`.
- Forbid flat `import "lib/math.ck";` to prevent importing everything accidentally.
- Provide completion items that show the originating namespace or file on the right side.
- Let selecting an importable completion insert the local name and add or update the needed import.

## Non-goals

- No import renaming in this phase (`println as print`).
- No nested Rust import trees.
- No wildcard imports.
- No transitive import visibility.

## Syntax and semantics

Supported import forms:

```ck
import terminal { println, clear };
import "lib/math.ck" { add, Vec2 };
import "lib/math.ck" as math;
```

Invalid forms:

```ck
import terminal;
import "lib/math.ck";
```

Built-in namespaces remain ambient. `terminal::println("hi")` works even if `terminal` has not been imported. A selective built-in import only adds the listed members as unqualified symbols in the current file.

User-file selective imports load and analyze the target file, then register only listed top-level `fun` and `struct` exports. Imports remain non-transitive: importing `a.ck` does not expose anything that `a.ck` imported.

Conflicts between local declarations, selective imports, aliases, and built-in namespaces produce redeclaration/conflict diagnostics. Unknown selected names produce diagnostics that identify the source and missing member.

## AST and resolver model

`ImportDeclaration` should represent a source and a mode:

- `ImportSource.BuiltinNamespace(name, range)` for sources like `terminal`.
- `ImportSource.FilePath(path, range)` for sources like `"lib/math.ck"`.
- `ImportMode.Namespace(alias)` for `as math`.
- `ImportMode.Selective(items)` for `{ println, clear }`.
- `ImportItem(name, range)` for each selected member.

This shape keeps the initial feature simple while leaving a natural place to add future `as` renames.

The resolver registers selective imports as normal visible symbols that point to the original binding. A call to `println()` after `import terminal { println };` resolves to the same built-in function binding as `terminal::println()`.

For file imports, the existing `SourceLoader` continues to load concrete files. The analyzer should reuse the current canonical-path cache so the same `.ck` file is parsed and analyzed at most once per compilation.

## Completion and auto-import behavior

Completion results have two categories:

1. Visible symbols already in scope. Applying them only replaces the typed prefix with `label` or `insertText`.
2. Importable candidates from built-in namespaces or workspace `.ck` files. Applying them also adds or updates an import group.

Examples:

- Typing `pri` offers `println` with right-side source `terminal`.
- Applying that item inserts `println()` and adds `import terminal { println };` if needed.
- If `import terminal { clear };` already exists, applying `println` updates it to `import terminal { clear, println };`.
- User-file candidates show their source path, such as `lib/math.ck`, and add `import "lib/math.ck" { add };`.

`CompletionItem` needs extra metadata:

- `sourceNamespace: String?` for the right-side UI text.
- `additionalTextEdits: List<TextEdit>` or a dedicated import edit field for auto-import changes.
- Optional priority/sort metadata so already-visible symbols appear before importable candidates.

The workbench completion row should render the label on the left and `sourceNamespace` on the right in muted text. Completion application should apply all edits atomically through the existing local edit / CRDT path and preserve function-call cursor placement.

If an importable candidate would conflict with a local symbol, the first implementation should hide that candidate rather than insert code that immediately produces a diagnostic.

## Workspace source index

Auto-import suggestions for user files require discovery beyond the current import graph. Add a lightweight source index capability next to `SourceLoader`:

- list `.ck` files visible from the current workspace;
- read sources for indexing;
- expose top-level `fun` and `struct` exports for completion.

`MapSourceLoader` can derive the index from map keys in tests. The production device workspace implementation can enumerate workspace documents. The compiler remains deterministic because imports still require explicit source paths; only IDE suggestions use the broader index.

For responsiveness, the first index can be parse-level: collect top-level declarations and use available syntax for detail text. Broken files should not break completion for the current file; skip invalid exports or keep partial results when safe.

## Testing plan

- Parser accepts selective built-in and file imports.
- Parser/analyzer rejects flat imports and bare built-in imports.
- Resolver accepts `println("x")` after `import terminal { println };`.
- Resolver keeps `terminal::println("x")` valid without imports.
- Resolver rejects non-selected names such as `clear()` after only importing `println`.
- User-file selected functions and structs are visible; non-selected exports are not.
- Conflicting selective imports produce diagnostics.
- Runtime tests execute selective imported built-in calls and user-file functions.
- IDE tests verify right-side namespace/source metadata.
- IDE/workbench tests verify auto-import insertion and update behavior.
- UI tests verify completion rows render both label and source text.

## Documentation and migration

`docs/LANGUAGE.md` should remove flat import examples and document:

- qualified built-in usage with `terminal::println()`;
- selective built-in imports with `import terminal { println };`;
- selective file imports with `import "math.ck" { add };`;
- namespace file imports with `import "math.ck" as math;`.

Existing CKL code using `import "file.ck";` must migrate to either a selective import list or a namespace alias.