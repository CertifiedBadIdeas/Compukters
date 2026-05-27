# English Localization Design

## Goal

Restore an `en_us.json` localization file for Compukter Kraft using only strings that are currently used by the mod.

## Scope

The first pass includes only keys that are directly required by the current code and current registry entries:

- `block.compukterkraft.computer_advanced`
- `block.compukterkraft.workbench`
- `item.compukterkraft.computer_advanced`
- `item.compukterkraft.workbench`
- `itemGroup.compukterkraft`
- `gui.compukterkraft.terminal.powered_off`
- `gui.compukterkraft.terminal.connecting`
- `gui.compukterkraft.tooltip.computer_id`
- `gui.compukterkraft.tooltip.copy`
- `commands.compukterkraft.generic.yes`
- `commands.compukterkraft.generic.no`
- `commands.compukterkraft.dump.action`

The file should exclude deleted ComputerCraft-era content and any localization keys for blocks, items, or systems that are no longer present in the current mod.

## Source Of Truth

Text should be recovered from the previous `en_us.json` in git history when the key still matches current behavior.

New text should only be written when:

- a currently used key did not exist in the old file, or
- the old wording is no longer appropriate for the current mod.

## File Location

The localization file lives at:

`modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/lang/en_us.json`

## Verification

Add a focused resource test in `:v1_21_1-neoforge` that loads `en_us.json` from the classpath and verifies the required keys are present.

Verification for completion:

1. `en_us.json` exists in the mod resources.
2. The required keys are present.
3. The focused `:v1_21_1-neoforge:test` run passes.

## Non-Goals

- No translation of vanilla keys such as `advMode.*`.
- No restoration of localization for removed blocks, peripherals, turtles, disks, or config screens from the old mod.
- No code behavior changes outside of resource coverage for localization.