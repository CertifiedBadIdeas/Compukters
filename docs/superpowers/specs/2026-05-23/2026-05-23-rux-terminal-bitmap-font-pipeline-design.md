# Rux Terminal Bitmap Font Pipeline Design

> Issue: [#49](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/49)

## Goal

Make Rux terminal glyph rendering use one readable bitmap font source instead of duplicated hand-written glyph tables in Rust and Kotlin.

## Decision

Keep the current terminal geometry: each text cell remains `6x9`, and each glyph body remains `5x7`. This preserves existing display layout, terminal dimensions, and machine/display behavior while allowing glyph quality to improve safely.

## Architecture

The pipeline is:

```text
assets/rux/fonts/rux-mono-5x7.font
  -> ./gradlew generateRuxFontTables
  -> native/rux-vm/src/generated/font_mono5x7.rs
  -> modules/core/.../GeneratedTerminalFont.kt
```

The source font file is the only file that should be edited when changing glyph shapes. Generated Rust and Kotlin tables are committed so normal Gradle/Cargo builds do not need a generator at runtime. The generator lives in the existing Gradle `build-scripts` included build, not as a separate Python toolchain.

## Font Scope

The first version covers printable ASCII `0x20..0x7E`, the box drawing glyphs currently used by firmware/UI, and one fallback glyph. Full Unicode, proportional text, antialiasing, TTF rasterization, and a larger cell size are out of scope.

## Compatibility

This does not change Rux Low ABI, machine profile layout, display pixel format, or terminal cell size. It only changes how built-in terminal text glyphs are sourced and rendered.

## Verification

Rust tests verify generated glyph coverage and key glyph differences. Kotlin tests verify that the serial text renderer uses the generated font and can render sample text through the existing `6x9` cell geometry.
