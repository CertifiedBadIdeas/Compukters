# BIOS Splash Design

## Goal

Add a short firmware-level boot splash that makes startup feel intentional and branded without changing the ROM terminal or shell architecture.

## Scope

- Change `firmware/bios.ck` only for runtime behavior.
- Add regression coverage in `RomScriptCompileTest.kt`.
- Do not add host-side Minecraft UI, texture, or native rendering paths.
- Do not change `rom/boot.ck`, `rom/terminal.ck`, or shell stdio behavior for this feature.

## User Experience

On startup, BIOS renders a pixel-art `COMPUKTER` splash with a small `KRAFT BIOS`/boot status line. The splash stays visible for roughly two seconds, then BIOS continues with the existing `boot.ck` lookup and launch flow.

If the display attaches or resizes during the splash, BIOS redraws the splash. If boot fails or `boot.ck` is missing, BIOS keeps using the existing status frame/error screen behavior.

## Architecture

The splash lives in `firmware/bios.ck` because it is firmware branding, not user workspace content. The implementation uses existing display primitives only:

- `display::clear`
- `display::fillRect`
- `display::blitMono`
- `display::present`

The delay is implemented as CKL control flow that yields while watching display events, not as host-side blocking UI.

## Testing

Add source-level regression tests that verify:

- BIOS has dedicated splash helpers.
- The splash runs before the `boot.ck` existence check.
- The splash uses display primitives, not stdout/terminal builtins.
- Existing firmware and ROM compile tests continue to pass.
