# Generated Localization API Split Design

## Goal

Generate three separate Kotlin localization APIs from `en_us.json` so production code can access raw keys, UI DSL
`Value<String>` values, and `Component` factories through distinct, non-overloaded entry points, without a redundant
`Compukterkraft` object level.

The generated APIs must provide:

1. Raw key access for every localization key.
2. `Value<String>` helpers for non-parameterized keys.
3. `Component` accessors for plain keys and `vararg` component factories for parameterized keys.

## Current Context

- Lang resources live in `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/lang`.
- Key format has changed to modid-first for most entries, for example `compukterkraft.gui.terminal.connecting`, but some
  keys such as `itemGroup.compukterkraft` do not use that shape.
- The generator already has a user-provided fix for over-eager child object discovery and must preserve that behavior.
- The UI DSL in `v1_21_1-common` already exposes `translatable(key: String): Value<String>`.
- Runtime code also uses Minecraft `Component.translatable(...)` directly.

## Design

### Source Of Truth

Use `en_us.json` as the sole generation input.

Generation must read the keys exactly as they appear in the lang file, then apply one normalization rule for API
structure: if the first key segment is exactly `compukterkraft`, drop that segment from the generated object path. No
other segment stripping or special-case rewriting is allowed.

### Generated API Shape

Generate three separate root objects in `v1_21_1-common`, each in its own file:

- `CompukterKeys`
- `CompukterTranslatable`
- `CompukterComponents`

All three trees are built from the normalized key path up to the leaf segment.

Examples:

- `compukterkraft.gui.terminal.connecting` -> `CompukterKeys.Gui.Terminal.CONNECTING`
- `compukterkraft.gui.terminal.connecting` -> `CompukterTranslatable.Gui.Terminal.connecting`
- `compukterkraft.gui.terminal.connecting` -> `CompukterComponents.Gui.Terminal.connecting`
- `compukterkraft.gui.tooltip.computer_id` -> `CompukterComponents.Gui.Tooltip.computerId(vararg args: Any)`
- `itemGroup.compukterkraft` -> `CompukterKeys.ItemGroup.COMPUKTERKRAFT`

### API Responsibilities

#### `CompukterKeys`

Generate raw key constants for every localization key.

Example:

- `const val CONNECTING = "compukterkraft.gui.terminal.connecting"`

#### `CompukterTranslatable`

Generate `Value<String>` getter properties only for non-parameterized localization entries.

Example:

- `val connecting: Value<String> get() = translatable(CompukterKeys.Gui.Terminal.CONNECTING)`

Parameterized keys do not get `Value<String>` helpers.

#### `CompukterComponents`

Generate `Component` getters for non-parameterized entries and `vararg` factory functions for parameterized entries.

Examples:

- `val connecting: Component get() = Component.translatable(CompukterKeys.Gui.Terminal.CONNECTING)`
- `fun computerId(vararg args: Any): Component = Component.translatable(CompukterKeys.Gui.Tooltip.COMPUTER_ID, *args)`

### Tree Construction Rules

- Object nodes use PascalCase.
- `CompukterTranslatable` properties use camelCase and no `Value` suffix because the object name already conveys the
  type.
- `CompukterComponents` getter and function names also use camelCase.
- Raw key leaves use UPPER_SNAKE_CASE.
- The child-discovery logic must remain prefix-aware so leaf nodes do not incorrectly create unrelated descendant
  objects.
- The generated tree must not create a `Compukterkraft` object node when the stripped leading namespace segment was only
  the modid prefix.

### Module Boundary

The generator reads lang resources from `v1_21_1-neoforge` and emits generated Kotlin sources consumed by
`v1_21_1-common`.

The Gradle task writes three output files into the generated source directory and registers that directory with the
`main` Kotlin source set of `v1_21_1-common`.

## Error Handling

The generation task must fail fast when:

- `en_us.json` is missing.
- `en_us.json` cannot be parsed into key/value entries.
- Two or more keys normalize to the same raw constant name inside one object.
- Two or more keys normalize to the same `Translatable` property name inside one object.
- Two or more keys normalize to the same `Components` getter or function name inside one object.

Failure messages must include the original localization keys that collided.

## Testing Strategy

Implement with TDD.

Required tests:

1. A generator test that verifies raw key generation in `CompukterKeys`.
2. A generator test that verifies `CompukterTranslatable` contains only non-parameterized properties.
3. A generator test that verifies `CompukterComponents` generates getters for plain strings and `vararg` functions for
   parameterized strings.
4. A generator test that verifies name-collision failures for each API surface.
5. A smoke test in `v1_21_1-common` that proves all three generated roots are available to normal Kotlin compilation.
6. A generator test that verifies non-modid-first keys such as `itemGroup.compukterkraft` are preserved structurally and
   do not lose their first segment.

## Non-Goals

- No automatic migration of all existing call sites.
- No type inference for placeholder argument types beyond `vararg args: Any`.
- No generation from non-English locale files.
- No restructuring of localization parity tests beyond what is required for the generator to keep working with the new
  key format.

## Acceptance Criteria

1. `en_us.json` drives generation of `CompukterKeys`, `CompukterTranslatable`, and `CompukterComponents`.
2. Every localization key gets a raw constant in `CompukterKeys`.
3. Only non-parameterized keys get `Value<String>` helpers in `CompukterTranslatable`.
4. Non-parameterized keys get `Component` getters in `CompukterComponents`.
5. Parameterized keys get `vararg` component factories in `CompukterComponents`.
6. The generator respects the user-fixed child discovery behavior and does not create spurious descendant objects.
7. The generator omits the redundant `Compukterkraft` object level only when the leading segment is exactly the modid
   prefix.
8. The build fails clearly on parse errors or normalized-name collisions.