# Generated Localization Split API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:
> executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate three Kotlin localization APIs from `en_us.json`: `CompukterKeys`, `CompukterTranslatable`, and
`CompukterComponents`.

**Architecture:** Keep generation in `build-scripts` and emit three generated Kotlin files into the `:v1_21_1-common`
source set. Build object trees from normalized key paths where a leading `compukterkraft` segment is dropped only when
it is the actual modid prefix, preserve the existing prefix-aware child discovery fix, and split behavior by API
surface: raw keys, `Value<String>`, and `Component` helpers.

**Tech Stack:** Gradle Kotlin DSL, Kotlin/JVM, JUnit 5 in `build-scripts`, `kotlin.test` in module tests, Minecraft
`Component`, existing `translatable(key: String): Value<String>` adapter.

**Execution note:** Do not create commits unless the user explicitly asks for them.

---

## File Structure

- Modify: `build-scripts/src/main/kotlin/LocalizationApiGenerator.kt`
- Modify: `build-scripts/src/main/kotlin/GenerateLocalizationApiTask.kt`
- Modify: `build-scripts/src/test/kotlin/LocalizationApiGeneratorTest.kt`
- Modify: `build-scripts/build.gradle.kts`
- Modify: `modules/v1_21_1/v1_21_1-common/build.gradle.kts`
- Modify:
  `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/ui/dsl/CompukterLangGenerationSmokeTest.kt`

### Task 1: Red-green the generator for split APIs and modid-first keys

**Files:**

- Modify: `build-scripts/src/test/kotlin/LocalizationApiGeneratorTest.kt`
- Modify: `build-scripts/src/main/kotlin/LocalizationApiGenerator.kt`

- [ ] **Step 1: Rewrite the generator test around the new split roots**

```kotlin
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalizationApiGeneratorTest {
    @Test
    fun generatesSplitApisForPlainStrings() {
        val rendered =
            LocalizationApiGenerator(
                packageName = "ru.lazyhat.compukterkraft.common.ui.dsl",
            ).generate(
                mapOf(
                    "compukterkraft.gui.terminal.connecting" to "Connecting...",
                ),
            )

        val keys = rendered.getValue("CompukterKeys.kt")
        val values = rendered.getValue("CompukterTranslatable.kt")
        val components = rendered.getValue("CompukterComponents.kt")

        assertTrue(keys.contains("object CompukterKeys"))
        assertTrue(keys.contains("object Gui"))
        assertTrue(keys.contains("const val CONNECTING = \"compukterkraft.gui.terminal.connecting\""))

        assertTrue(values.contains("object CompukterTranslatable"))
        assertTrue(values.contains("val connecting: Value<String>"))
        assertTrue(values.contains("translatable(CompukterKeys.Gui.Terminal.CONNECTING)"))

        assertTrue(components.contains("object CompukterComponents"))
        assertTrue(components.contains("val connecting: Component"))
        assertTrue(components.contains("Component.translatable(CompukterKeys.Gui.Terminal.CONNECTING)"))
    }
}
```

- [ ] **Step 2: Run the build-logic tests to confirm red state**

Run: `./gradlew -p build-scripts test`

Expected: FAIL because `LocalizationApiGenerator.generate` still returns one rendered source instead of three files and
still models the old API.

- [ ] **Step 3: Implement the minimal split generator**

```kotlin
class LocalizationApiGenerator(
    private val packageName: String,
) {
    fun generate(entries: Map<String, String>): Map<String, String> =
        mapOf(
            "CompukterKeys.kt" to renderKeys(entries),
            "CompukterTranslatable.kt" to renderTranslatable(entries),
            "CompukterComponents.kt" to renderComponents(entries),
        )
}
```

Implementation notes:

- Build object paths from the full key path except the leaf segment, then drop the first segment only when it equals
  `compukterkraft`.
- Preserve the existing prefix-aware child discovery logic when rendering descendants.
- `CompukterTranslatable` uses camelCase names without a `Value` suffix.

- [ ] **Step 4: Re-run the build-logic tests to confirm green state**

Run: `./gradlew -p build-scripts test`

Expected: PASS.

- [ ] **Step 5: Review the diff for Task 1 coverage**

Check that:

- modid-first keys stay intact,
- three files are rendered,
- `CompukterKeys`, `CompukterTranslatable`, and `CompukterComponents` share the same tree shape.

### Task 2: Add red-green coverage for placeholders, components factories, and collisions

**Files:**

- Modify: `build-scripts/src/test/kotlin/LocalizationApiGeneratorTest.kt`
- Modify: `build-scripts/src/main/kotlin/LocalizationApiGenerator.kt`

- [ ] **Step 1: Extend tests for format strings and API-surface-specific collisions**

```kotlin
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows

class LocalizationApiGeneratorTest {
    @Test
    fun skipsTranslatableForParameterizedEntriesAndGeneratesComponentFactory() {
        val rendered =
            LocalizationApiGenerator(
                packageName = "ru.lazyhat.compukterkraft.common.ui.dsl",
            ).generate(
                mapOf(
                    "compukterkraft.gui.tooltip.computer_id" to "Computer ID: %s",
                ),
            )

        val values = rendered.getValue("CompukterTranslatable.kt")
        val components = rendered.getValue("CompukterComponents.kt")

        assertFalse(values.contains("computerId"))
        assertTrue(components.contains("fun computerId(vararg args: Any): Component"))
        assertTrue(components.contains("Component.translatable(CompukterKeys.Gui.Tooltip.COMPUTER_ID, *args)"))

        val oddKeys =
            LocalizationApiGenerator(
                packageName = "ru.lazyhat.compukterkraft.common.ui.dsl",
            ).generate(
                mapOf(
                    "itemGroup.compukterkraft" to "Compukter Kraft",
                ),
            ).getValue("CompukterKeys.kt")

        assertTrue(oddKeys.contains("object ItemGroup"))
        assertTrue(oddKeys.contains("const val COMPUKTERKRAFT = \"itemGroup.compukterkraft\""))
    }

    @Test
    fun failsWhenComponentNamesCollapseInsideOneObject() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                LocalizationApiGenerator(
                    packageName = "ru.lazyhat.compukterkraft.common.ui.dsl",
                ).generate(
                    mapOf(
                        "compukterkraft.gui.terminal.foo-bar" to "A",
                        "compukterkraft.gui.terminal.foo_bar" to "B",
                    ),
                )
            }

        assertTrue((error.message ?: "").contains("foo-bar"))
        assertTrue((error.message ?: "").contains("foo_bar"))
    }
}
```

- [ ] **Step 2: Run build-logic tests to confirm red state**

Run: `./gradlew -p build-scripts test`

Expected: FAIL because parameterized component factories and per-surface collision checks are not implemented yet.

- [ ] **Step 3: Implement placeholder-aware rendering and collision validation**

```kotlin
private fun renderTranslatableEntry(entry: GeneratedLocalizationEntry): String? =
    if (entry.isParameterized) {
        null
    } else {
        "val ${entry.propertyName}: Value<String> get() = translatable(${entry.keyReference})"
    }

private fun renderComponentEntry(entry: GeneratedLocalizationEntry): String =
    if (entry.isParameterized) {
        "fun ${entry.propertyName}(vararg args: Any): Component = Component.translatable(${entry.keyReference}, *args)"
    } else {
        "val ${entry.propertyName}: Component get() = Component.translatable(${entry.keyReference})"
    }
```

Implementation notes:

- Detect placeholders with the existing placeholder regex.
- Validate collisions separately for constants, translatable properties, and component members.
- Keep reporting original keys in all collision errors.

- [ ] **Step 4: Re-run build-logic tests to confirm green state**

Run: `./gradlew -p build-scripts test`

Expected: PASS.

- [ ] **Step 5: Review the diff for Task 2 coverage**

Check that:

- `Translatable` omits parameterized entries,
- `Components` emits `vararg` functions for parameterized entries,
- collisions are enforced per API surface.

### Task 3: Wire three generated files into `:v1_21_1-common` and prove compile access

**Files:**

- Modify: `build-scripts/src/main/kotlin/GenerateLocalizationApiTask.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/build.gradle.kts`
- Modify:
  `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/ui/dsl/CompukterLangGenerationSmokeTest.kt`

- [ ] **Step 1: Rewrite the smoke test for all three generated roots**

```kotlin
package ru.lazyhat.compukterkraft.common.ui.dsl

import net.minecraft.network.chat.Component
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompukterLangGenerationSmokeTest {
    @Test
    fun generatedLocalizationApisAreAvailableToCommonCode() {
        assertEquals(
            "compukterkraft.gui.terminal.connecting",
            CompukterKeys.Compukterkraft.Gui.Terminal.CONNECTING,
        )
        assertTrue(CompukterTranslatable.Compukterkraft.Gui.Terminal.connecting.value.isNotBlank())
        assertTrue(CompukterComponents.Compukterkraft.Gui.Terminal.connecting is Component)
        assertTrue(
            CompukterComponents.Compukterkraft.Gui.Tooltip.computerId("42") is Component,
        )
    }
}
```

- [ ] **Step 2: Run the common-module test to confirm red state**

Run: `./gradlew :v1_21_1-common:test --tests ru.lazyhat.compukterkraft.common.ui.dsl.CompukterLangGenerationSmokeTest`

Expected: FAIL because the generated source task still writes `CompukterLang.kt` and the old API shape.

- [ ] **Step 3: Update the Gradle task to emit three files and wire them into compilation**

```kotlin
@TaskAction
fun generateSources() {
    val entries = parseLangEntries(langFile.get().asFile.readText())
    val renderedFiles = LocalizationApiGenerator(packageName.get()).generate(entries)

    renderedFiles.forEach { (fileName, source) ->
        val outputFile = outputDirectory.file("ru/lazyhat/compukterkraft/common/ui/dsl/$fileName").get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(source)
    }
}
```

```kotlin
val generateLocalizationApi = tasks.register<GenerateLocalizationApiTask>("generateLocalizationApi") {
    langFile.set(project(":v1_21_1-neoforge").layout.projectDirectory.file("src/main/resources/assets/compukterkraft/lang/en_us.json"))
    packageName.set("ru.lazyhat.compukterkraft.common.ui.dsl")
    outputDirectory.set(layout.buildDirectory.dir("generated/sources/localizationApi/kotlin"))
}
```

Implementation notes:

- Update the object-path normalization so it drops only the leading `compukterkraft` segment when that segment is
  actually present.
- Keep the generated package unchanged.
- Reuse one generated source directory; only the file set changes.

- [ ] **Step 4: Re-run focused verification for build logic and module integration**

Run: `./gradlew -p build-scripts test`

Expected: PASS.

Run: `./gradlew :v1_21_1-common:test --tests ru.lazyhat.compukterkraft.common.ui.dsl.CompukterLangGenerationSmokeTest`

Expected: PASS.

- [ ] **Step 5: Run final verification before handoff**

Run: `./gradlew -p build-scripts test`

Expected: PASS.

Run: `./gradlew :v1_21_1-common:test --tests ru.lazyhat.compukterkraft.common.ui.dsl.CompukterLangGenerationSmokeTest`

Expected: PASS.