# IDE Autocomplete Trigger Design

## Goal

Make the in-game IDE behave more like a conventional code editor: keep manual completion via `Ctrl+Space`, preserve dot-triggered member completion, add completion popup on ordinary word typing so keyword completion becomes visible during typing, and remove the current auto-popup after `import `.

## Scope

This design covers only completion triggering and completion-flow UX.

Included:
- Autocomplete popup while typing ordinary identifier prefixes.
- Keyword completions becoming visible through ordinary typing, not only manual completion.
- Keeping `Ctrl+Space` as a manual fallback.
- Keeping `.` as the member-completion trigger.
- Removing the special-case popup after `import `.
- Context-aware completion re-trigger rules after accepting a suggestion.

Excluded:
- Auto-import.
- New import-specific completion UX.
- Changes to completion ranking or fuzzy matching.
- Changes to completion item rendering.

## Current State

The current editor behavior is split between the compiler-side completion engine and the workbench store:

- `LanguageIde` already produces keyword completions, module member completions, and general identifier completions.
- `WorkbenchStore` already auto-opens completion after `.` and after typing a space following `import`.
- `Ctrl+Space` already exists as a manual trigger.

The mismatch is not missing completion data. The mismatch is that popup-trigger behavior is narrow in one place and too special-cased in another place.

## Target Behavior

### 1. Word Trigger

When the user types an ordinary identifier-like prefix, the editor should auto-open completion if the cursor is inside a valid identifier context and the prefix is non-empty.

Examples:
- `wh` shows `while` and `when`.
- `ret` shows `return`.
- `tru` shows `true`.

This should make keyword completion discoverable during normal typing instead of requiring manual completion.

### 2. Dot Trigger

Typing `.` should continue to auto-open member completion exactly as it does now.

This remains the primary structural trigger for member access and should not be weakened by the new word-trigger logic.

### 3. Import Behavior

Typing a space after `import` should no longer auto-open completion.

Reasoning:
- It is a special-case popup that does not fit the broader editor model.
- It adds UI magic in the store layer for one narrow syntax path.
- The desired direction is a more general IDE feel, not more import-specific exceptions.

Manual completion for import contexts may remain available through `Ctrl+Space` if the completion engine already supports it, but there should be no automatic popup on `import `.

### 4. Manual Trigger

`Ctrl+Space` remains unchanged as a manual fallback.

It is still required for users who want explicit control or whose current typing context should not auto-open suggestions.

### 5. Accept and Re-trigger Rules

Accepting a completion should not blindly reopen the popup.

Rules:
- After accepting an ordinary keyword completion such as `while ` or `return `, the popup stays closed.
- After accepting a normal identifier completion, the popup stays closed unless a later typed character creates a new trigger context.
- Dot-triggered flow remains user-driven: the next popup appears when the user types `.`.
- There is no special re-trigger behavior for `import`.

This keeps the editor responsive without creating a popup loop.

## Architecture

### Compiler Layer Responsibility

The compiler-side IDE code remains responsible for determining which completion items exist for the source text at a given cursor position.

Relevant responsibilities:
- Detect identifier-like prefix context.
- Return matching keywords, identifiers, and member completions.
- Keep completion semantics consistent regardless of whether completion was opened manually or automatically.

The compiler layer should not know why completion was opened.

### Workbench Layer Responsibility

The workbench store remains responsible for deciding when the popup should open.

Relevant responsibilities:
- Detect typed-character trigger conditions.
- Open completion after `.`.
- Open completion after ordinary identifier-like typing.
- Stop opening completion after `import `.
- Preserve manual `Ctrl+Space` behavior.

This preserves the current boundary: language logic in `compiler`, interaction logic in `core`.

## Proposed Changes

### `SourceTextSupport`

Add or consolidate helper logic that can answer whether the cursor is currently in an identifier-like completion context.

The store-trigger rule and compiler completion rule should rely on compatible prefix detection instead of duplicating slightly different heuristics.

### `LanguageIde`

Keep existing keyword and identifier completion generation, but ensure the code path used for ordinary word typing is explicit and stable.

The key outcome is not a new completion type. The key outcome is that ordinary typed prefixes are treated as a first-class completion context.

### `WorkbenchStore`

Update `charTyped()` so that:
- `.` still opens completion.
- typing a letter, digit, or underscore inside an identifier-like prefix can open completion.
- typing space after `import` no longer opens completion.

Update acceptance flow so it does not contain import-specific re-trigger behavior.

### `WorkbenchEditorSupport`

No new behavior is required here beyond preserving existing completion insertion semantics.

If completion insertion already uses `insertText` when available, that behavior stays as-is.

## Data Flow

1. User types a character.
2. `WorkbenchStore.charTyped()` updates editor text and refreshes IDE state.
3. The store checks whether the typed character created a valid completion trigger:
   - member trigger via `.`;
   - identifier-like trigger via ordinary word typing.
4. If yes, the store requests completion from the existing IDE snapshot path.
5. `LanguageIde` returns context-appropriate items.
6. The editor state exposes popup items and selection.
7. On accept, the text updates and the popup closes unless a future typed character creates a new trigger.

## Error Handling

The trigger layer should fail quietly.

If the source text is temporarily incomplete or the IDE snapshot cannot produce meaningful items:
- the editor should simply keep the popup closed;
- no exception should leak into UI interaction;
- manual `Ctrl+Space` should remain available as fallback behavior.

## Testing Strategy

### Compiler Tests

Add or update tests that prove ordinary prefixes produce expected completions:
- `wh` includes `while` and `when`.
- `ret` includes `return`.
- `tru` includes `true`.

These tests verify that keyword completion is valid in ordinary word contexts.

### Store Tests

Add or update tests that prove trigger behavior:
- typing `.` opens completion;
- typing an identifier prefix opens completion;
- typing space after `import` does not open completion;
- `Ctrl+Space` still opens completion manually.

### Accept-flow Tests

Add tests ensuring accepted completion does not cause spurious popup loops:
- accepting `while ` closes the popup;
- accepting an identifier completion does not reopen completion without a new trigger;
- import-specific re-trigger behavior is absent.

## Risks

### Popup Noise

If the identifier trigger is too broad, the popup may appear too often.

Mitigation:
- trigger only for non-empty identifier-like prefixes;
- avoid symbol contexts that are clearly not word completion;
- preserve manual completion as the escape hatch.

### Trigger Drift Between UI and Compiler

If the store decides a context is valid but the compiler does not, the popup may open with empty results.

Mitigation:
- share or align prefix-detection helpers between the trigger logic and completion logic.

## Success Criteria

- Typing ordinary word prefixes opens completion for keywords and identifiers.
- Typing `.` still opens member completion.
- Typing `import ` no longer opens completion automatically.
- `Ctrl+Space` still works.
- Accepting completion does not cause noisy popup loops.
- Existing completion behavior outside these trigger rules remains unchanged.