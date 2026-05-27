# CKL Format Trigger Design

Date: 2026-05-01

## Goal

Expose the existing CKL Format Document and Cleanup Document APIs through user-facing Workbench editor triggers.

The MVP adds explicit manual triggers only:

- toolbar buttons for Format and Cleanup;
- keyboard shortcuts for Format and Cleanup.

Automatic format-on-save, server-side formatting commands, and extra settings are out of scope for this iteration.

## Current context

The CKL formatter and cleanup pipeline already exists behind the IDE facade:

- `LanguageIde.formatDocument(path, source)` formats CKL source and organizes imports.
- `LanguageIde.cleanupDocument(path, source)` formats CKL source and removes unused selective imports when semantic analysis succeeds.
- `WorkbenchIdeFacade.formatDocument(path, source)` and `cleanupDocument(path, source)` expose those actions to the Workbench layer.

The Workbench editor is driven by `WorkbenchStore`. UI code should stay thin and delegate editing behavior to the store.

## User-facing behavior

When a CKL document is open in the Workbench editor:

- the toolbar shows `Format` and `Clean` actions;
- `Format` calls the existing formatter and applies returned edits;
- `Clean` calls the existing cleanup action and applies returned edits;
- `Ctrl+Alt+F` triggers Format;
- `Ctrl+Alt+L` triggers Cleanup.

If no document is open, toolbar actions are disabled and shortcuts are no-ops.

If the formatter or cleanup action returns no edits, the editor text is unchanged. Diagnostics returned by the formatter are not shown through a new notification system in this MVP; the regular IDE analysis/status path remains the only user-facing diagnostic surface.

## Architecture

Add store-level trigger methods to `WorkbenchStore`, for example:

- `formatOpenDocument(visibleEditorLines: Int)`
- `cleanupOpenDocument(visibleEditorLines: Int)`

Each method:

1. reads `state.openDocument` and `state.editor.text`;
2. returns immediately when no document is open;
3. calls the matching `WorkbenchIdeFacade` method;
4. applies returned `TextEdit`s through the existing local edit/CRDT path;
5. closes completion UI and refreshes IDE analysis after edits are applied;
6. keeps the cursor visible using the provided visible editor line count.

Returned edits should be applied from highest `startOffset` to lowest `startOffset`. The current formatter returns a full-document edit, but ordering edits this way keeps the store correct if the formatter later returns multiple edits.

The toolbar remains a pure UI layer:

- add `Format` and `Clean` buttons to `buildToolbar`;
- enable them only when `store.state.openDocument != null`;
- delegate clicks to the store methods.

The keyboard path remains in `WorkbenchStore.keyPressed`:

- add `KeyCodes.KEY_F`, `KeyCodes.KEY_L`, and `KeyCodes.MOD_ALT` if missing;
- handle `Ctrl+Alt+F` before normal text input as Format;
- handle `Ctrl+Alt+L` before normal text input as Cleanup.

## Error handling

Formatting and cleanup must be conservative:

- no open document: no-op;
- no edits returned: no-op;
- invalid edit offsets: ignore that edit instead of corrupting text;
- formatter diagnostics without edits: no text mutation.

The store does not create a separate user notification mechanism in this task.

## Testing

Add or extend Workbench tests:

- store method applies `formatDocument` edits returned by `WorkbenchIdeFacade`;
- store method applies `cleanupDocument` edits returned by `WorkbenchIdeFacade`;
- `Ctrl+Alt+F` triggers format;
- `Ctrl+Alt+L` triggers cleanup;
- toolbar exposes Format/Clean actions and delegates to the store if existing UI tests can assert this without brittle pixel coupling.

Verification commands:

- targeted Workbench tests;
- `./gradlew :core:test`;
- `./gradlew test`.
