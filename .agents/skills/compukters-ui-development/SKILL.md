---
name: compukters-ui-development
description: Use when creating or modifying player-facing Compukters screens, IDE windows, dialogs, HUD controls, layout, icons, fonts or UI input. Reuse shared components and the established Islands style. Do not use for world or block rendering, or graphics drawn by Guest programs.
---

# Compukters UI Development

Keep new UI consistent with the existing Compukters interface through shared components, rather than recreating their appearance in each screen. Apply this alongside `compukters-neoforge-integration` for Minecraft client changes; repository workflow and verification scope remain governed by `AGENTS.md`.

## Find the Owning Components

Read the client presentation section of [tooling ownership](../../../docs/contributors/architecture/tooling.md) and inspect a working control of the same kind before editing. The established baseline is IntelliJ IDEA Islands Dark, with restrained colors, thin borders, rounded floating windows and consistent spacing. Treat explicit user design choices as authoritative.

For IDE changes, start under `modules/minecraft/shared/common/src/main/kotlin/ru/lazyhat/compukters/impl/ide/`:

- `IdeControls` and `IdeDrawBuilder.target` create control surfaces, borders and hit bounds together. Use their neutral/primary/standard roles deliberately; a text label alone is not a button.
- `IdeWindowSurface` owns floating-window borders, corners, padding and shadows. Register a new window kind there instead of drawing custom chrome.
- `IdeColors` owns theme roles. Reuse a matching role rather than introducing per-window colors or borrowing Guest terminal palette entries.
- `IdeCodeFontProfile`, `IdeDrawBuilder.centeredUi` and shared text/layout helpers own font metrics, centering and baseline adjustment. Avoid per-label baseline corrections.
- `IdeDrawLayers` and `IdeFramePainter` own ordering. Reuse the layer family of the containing window or popup.
- `IdeToolbarIconAtlas` and existing item-icon rendering own icons and their sizes. Keep icon bounds within the control; use the same primitives at the requested size.
- `IdeInputAdapter`, `IdeScreenController` and `CompuktersUiViewport` own input routing and scale conversion. Draw bounds and hit bounds must describe the same control in the same coordinate space.

Inspect `IdeStatusDetailsRenderer`, the project switcher in `IdeRenderer`, and `IdeToolbar` for working compositions. Other screens should reuse their owning shared surfaces, fields and input helpers. If a needed reusable behavior is absent, add it to the appropriate shared owner and consume it from the screen; do not clone it into both Minecraft version adapters.

Keep canonical presentation and geometry in shared sources. Version leaves should adapt drawing/input APIs only where the pinned Minecraft versions differ. Preserve the Guest terminal's grid, colors and terminal-owned content; its surrounding surface uses `TerminalSurfaceStyle`.

## Compose Controls Correctly

Use shared controls for action buttons, close buttons and selectable cards. Preserve enabled, selected and focus semantics, tooltips, and keyboard dismissal where applicable. Choose visual roles to match neighboring controls, not merely the component's default argument.

Control target z-indices are not paint z-indices: `IdeControls` draws a control background at `target.zIndex - BACKGROUND_OFFSET`, with its border one layer higher. A target placed just above a floating window can therefore leave the entire button painted behind that window. Use constants such as `Z_PROJECT_SWITCHER_TARGET` or `Z_DIALOG_TARGET`; keep control backgrounds above the containing surface and labels/icons above their backgrounds. Do not invent nearby numeric offsets without checking the actual painter order.

Use the same shared font metrics for captions, centering, clipping and hit testing. Fit long text to its bounds without overlapping adjacent buttons. Keep icon enlargement separate from text metrics and preserve the requested card/button geometry.

## Verify Appearance and Interaction

Choose focused evidence for the changed behavior. Layout-model assertions alone cannot prove that a button is visible: for layering or surface bugs, exercise `IdeFramePainter` with a recording canvas and inspect the final painted surface/border after all overlapping operations. Check enabled and disabled states where relevant. Use existing input tests for click routing, scale transforms, scrolling, focus and dismissal.

Run applicable formatter/lint and compile affected version adapters. Identify which Minecraft target currently owns canonical shared source roots before formatting; formatting an inactive generated mirror does not format the source owner. Follow repository verification policy rather than adding a full build to every UI edit.

For GPU rendering or a subjective visual change, define a bounded in-client comparison: initial world/screen state, UI scale, actions and expected result. A client launch or a passing model test is not a visual inspection. If the client cannot be observed, report that specific check as pending rather than claim appearance is confirmed. Use user screenshots as evidence and correct the demonstrated issue without redesigning unrelated UI.
