# Russian Localization Design

## Goal

Add a `ru_ru.json` localization file for Compukter Kraft with the same active translation keys as `en_us.json`.

## Scope

The Russian localization must mirror the current English localization key set exactly.

Included keys:

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

Text style should use natural Russian phrasing while keeping the `Compukter Kraft` brand name unchanged.

## File Location

The localization file lives at:

`modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/lang/ru_ru.json`

## Verification

Use one shared resource test for all localization files in the lang directory.

The test should:

1. Load `en_us.json` as the source of truth for the key set.
2. Load every other `*.json` localization file in the same lang directory.
3. Verify that each localization file contains the same keys as `en_us.json`.

## Non-Goals

- No expansion of the current localization scope.
- No addition of keys that are not already present in `en_us.json`.
- No code behavior changes beyond localization resources and localization coverage tests.