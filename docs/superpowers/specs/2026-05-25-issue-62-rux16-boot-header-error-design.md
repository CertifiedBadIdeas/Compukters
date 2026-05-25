# Rux16 Boot Header Error Design

> Issue: [#62](https://github.com/CertifiedBadIdeas/Compukter-Kraft/issues/62)

## Context

Rux16 BIOS can now load a one-block stage2 program from `storage0` and jump to the RAM entry point. The successful boot path is covered, but the BIOS boot contract also needs a deterministic failure path for corrupt boot metadata.

## Goal

Cover the first corrupt boot header case: invalid magic in block 0 must halt in BIOS, expose a deterministic control marker, and avoid jumping to stage2.

## Architecture

The existing test BIOS already compares the loaded block-0 magic against `RUXB`. On mismatch, it writes `0xB` to `CONTROL_PANIC_CODE` and halts. This slice adds an integration test that builds storage media with an invalid block-0 magic and a valid stage2 in block 1. The expected result is no stage2 debug output and a visible control marker from BIOS.

This is a fail-closed boot path. There is no fallback to bundled firmware, no host-side decode, and no attempt to run stage2 when the header is invalid.

## Out of Scope

- Checksums or signatures.
- Multi-block bounds validation.
- Missing media and storage I/O error mapping.
- User-facing BIOS text rendering.

## Verification

- Native Rust test: invalid boot header magic halts before stage2 and reports `0xB` through control MMIO.
- Regression: successful stage2 boot test still passes.
