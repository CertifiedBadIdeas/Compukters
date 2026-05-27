# План реализации разделённого API локализаций

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:
> executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Сгенерировать три Kotlin API локализаций из `en_us.json`: `CompukterKeys`, `CompukterTranslatable` и
`CompukterComponents`.

**Architecture:** Генерацию оставляем в `build-scripts` и выпускаем три generated Kotlin file в source set модуля
`:v1_21_1-common`. Object tree строятся из нормализованных key path, где ведущий сегмент `compukterkraft` убирается
только если это реальный modid-prefix, сохраняют существующую prefix-aware правку child discovery и разделяют
ответственность по API surface: raw keys, `Value<String>` и `Component` helper-ы.

**Tech Stack:** Gradle Kotlin DSL, Kotlin/JVM, JUnit 5 в `build-scripts`, `kotlin.test` в module tests, Minecraft
`Component`, существующий adapter `translatable(key: String): Value<String>`.

**Execution note:** Не создавать коммиты, пока пользователь явно этого не попросит.

---

## Структура файлов

- Modify: `build-scripts/src/main/kotlin/LocalizationApiGenerator.kt`
- Modify: `build-scripts/src/main/kotlin/GenerateLocalizationApiTask.kt`
- Modify: `build-scripts/src/test/kotlin/LocalizationApiGeneratorTest.kt`
- Modify: `build-scripts/build.gradle.kts`
- Modify: `modules/v1_21_1/v1_21_1-common/build.gradle.kts`
- Modify:
  `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/ui/dsl/CompukterLangGenerationSmokeTest.kt`

### Task 1: Провести red-green цикл генератора для split API и modid-first keys

**Files:**

- Modify: `build-scripts/src/test/kotlin/LocalizationApiGeneratorTest.kt`
- Modify: `build-scripts/src/main/kotlin/LocalizationApiGenerator.kt`

- [ ] **Step 1: Переписать generator test под новые split roots**

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

- [ ] **Step 2: Запустить build-logic tests и подтвердить красное состояние**

Run: `./gradlew -p build-scripts test`

Expected: FAIL, потому что `LocalizationApiGenerator.generate` всё ещё возвращает один rendered source вместо трёх
файлов и моделирует старый API.

- [ ] **Step 3: Реализовать минимальный split generator**

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

- Строить object paths из полного key path кроме leaf segment, а затем убирать первый сегмент только если он равен
  `compukterkraft`.
- Сохранить существующую prefix-aware логику child discovery при рендере descendants.
- `CompukterTranslatable` использует camelCase имена без суффикса `Value`.

- [ ] **Step 4: Повторно запустить build-logic tests и подтвердить зелёное состояние**

Run: `./gradlew -p build-scripts test`

Expected: PASS.

- [ ] **Step 5: Просмотреть diff и проверить покрытие Task 1**

Проверить, что:

- modid-first keys остаются как есть,
- рендерятся три файла,
- `CompukterKeys`, `CompukterTranslatable` и `CompukterComponents` имеют одинаковую форму дерева.

### Task 2: Добавить red-green покрытие для placeholders, component factories и collisions

**Files:**

- Modify: `build-scripts/src/test/kotlin/LocalizationApiGeneratorTest.kt`
- Modify: `build-scripts/src/main/kotlin/LocalizationApiGenerator.kt`

- [ ] **Step 1: Расширить tests для format strings и API-surface-specific collisions**

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

- [ ] **Step 2: Запустить build-logic tests и подтвердить красное состояние**

Run: `./gradlew -p build-scripts test`

Expected: FAIL, потому что parameterized component factories и per-surface collision checks ещё не реализованы.

- [ ] **Step 3: Реализовать placeholder-aware rendering и collision validation**

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

- Определять placeholders текущим placeholder regex.
- Валидировать collisions отдельно для constants, translatable properties и component members.
- Во всех collision errors сохранять исходные keys.

- [ ] **Step 4: Повторно запустить build-logic tests и подтвердить зелёное состояние**

Run: `./gradlew -p build-scripts test`

Expected: PASS.

- [ ] **Step 5: Просмотреть diff и проверить покрытие Task 2**

Проверить, что:

- `Translatable` пропускает parameterized entries,
- `Components` выпускает `vararg` functions для parameterized entries,
- collisions валидируются отдельно по API surface.

### Task 3: Подключить три generated file в `:v1_21_1-common` и доказать compile access

**Files:**

- Modify: `build-scripts/src/main/kotlin/GenerateLocalizationApiTask.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/build.gradle.kts`
- Modify:
  `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/ui/dsl/CompukterLangGenerationSmokeTest.kt`

- [ ] **Step 1: Переписать smoke test под все три generated roots**

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

- [ ] **Step 2: Запустить common-module test и подтвердить красное состояние**

Run: `./gradlew :v1_21_1-common:test --tests ru.lazyhat.compukterkraft.common.ui.dsl.CompukterLangGenerationSmokeTest`

Expected: FAIL, потому что generated source task всё ещё пишет `CompukterLang.kt` и старую форму API.

- [ ] **Step 3: Обновить Gradle task так, чтобы он писал три файла и подключал их в compilation**

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

- Обновить нормализацию object path так, чтобы она убирала только ведущий сегмент `compukterkraft`, когда он реально
  присутствует.
- Сохранить generated package без изменений.
- Использовать ту же generated source directory; меняется только набор файлов.

- [ ] **Step 4: Повторно запустить точечную проверку build logic и module integration**

Run: `./gradlew -p build-scripts test`

Expected: PASS.

Run: `./gradlew :v1_21_1-common:test --tests ru.lazyhat.compukterkraft.common.ui.dsl.CompukterLangGenerationSmokeTest`

Expected: PASS.

- [ ] **Step 5: Запустить финальную verification перед handoff**

Run: `./gradlew -p build-scripts test`

Expected: PASS.

Run: `./gradlew :v1_21_1-common:test --tests ru.lazyhat.compukterkraft.common.ui.dsl.CompukterLangGenerationSmokeTest`

Expected: PASS.