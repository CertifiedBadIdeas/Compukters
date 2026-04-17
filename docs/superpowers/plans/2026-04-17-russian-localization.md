# Russian Localization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `ru_ru.json` localization file for Compukter Kraft and enforce locale key parity against `en_us.json`.

**Architecture:** Add one Russian localization resource that mirrors the English key set and replace the current English-only coverage with a shared locale parity test. The shared test uses `en_us.json` as the key source of truth and checks every other localization file in the lang directory.

**Tech Stack:** Kotlin tests, Gradle, NeoForge resources, JSON localization assets

---

### Task 1: Add Russian Localization Resource

**Files:**
- Create: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/lang/ru_ru.json`
- Reference: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/lang/en_us.json`

- [ ] **Step 1: Write the failing test**

```kotlin
assertTrue(localeNames.contains("ru_ru.json"), "Expected Russian localization resource to exist")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :v1_21_1-neoforge:test --tests 'ru.lazyhat.compukterkraft.impl.platform.LocalizationParityResourceTest' --console=plain`
Expected: FAIL because `ru_ru.json` does not exist yet.

- [ ] **Step 3: Write minimal implementation**

```json
{
  "block.compukterkraft.computer_advanced": "Продвинутый компьютер",
  "block.compukterkraft.workbench": "Верстак",
  "item.compukterkraft.computer_advanced": "Продвинутый компьютер",
  "item.compukterkraft.workbench": "Верстак",
  "itemGroup.compukterkraft": "Compukter Kraft",
  "gui.compukterkraft.terminal.powered_off": "Компьютер выключен. Сначала включите его.",
  "gui.compukterkraft.terminal.connecting": "Подключение...",
  "gui.compukterkraft.tooltip.computer_id": "ID компьютера: %s",
  "gui.compukterkraft.tooltip.copy": "Скопировать в буфер обмена",
  "commands.compukterkraft.generic.yes": "Д",
  "commands.compukterkraft.generic.no": "Н",
  "commands.compukterkraft.dump.action": "Показать больше информации об этом компьютере"
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :v1_21_1-neoforge:test --tests 'ru.lazyhat.compukterkraft.impl.platform.LocalizationParityResourceTest' --console=plain`
Expected: PASS for the resource existence and locale parity checks.

- [ ] **Step 5: Commit**

```bash
git add modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/lang/ru_ru.json
git commit -m "feat: add russian localization"
```

### Task 2: Replace English-Only Coverage With Shared Locale Parity Test

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/platform/EnglishLocalizationResourceTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
assertEquals(englishKeys, localizedKeys, "Expected $localeName to contain the same keys as en_us.json")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :v1_21_1-neoforge:test --tests 'ru.lazyhat.compukterkraft.impl.platform.LocalizationParityResourceTest' --console=plain`
Expected: FAIL before `ru_ru.json` is added or while the test still only checks `en_us.json`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
class LocalizationParityResourceTest {
    @Test
    fun allLocalizationsContainSameKeysAsEnglish() {
        val langRoot = checkNotNull(javaClass.classLoader.getResource("assets/compukterkraft/lang"))
        val langDirectory = Paths.get(langRoot.toURI())
        val englishKeys = localizationKeys(langDirectory.resolve("en_us.json"))

        Files.list(langDirectory)
            .filter { it.fileName.toString().endsWith(".json") }
            .filter { it.fileName.toString() != "en_us.json" }
            .forEach { localePath ->
                val localizedKeys = localizationKeys(localePath)
                assertEquals(englishKeys, localizedKeys, "Expected ${'$'}{localePath.fileName} to contain the same keys as en_us.json")
            }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :v1_21_1-neoforge:test --tests 'ru.lazyhat.compukterkraft.impl.platform.LocalizationParityResourceTest' --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/platform/EnglishLocalizationResourceTest.kt
git commit -m "test: verify locale key parity"
```

### Task 3: Run Final Targeted Verification

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/lang/ru_ru.json`
- Test: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/platform/EnglishLocalizationResourceTest.kt`

- [ ] **Step 1: Run focused verification suite**

Run: `./gradlew :v1_21_1-neoforge:test --tests 'ru.lazyhat.compukterkraft.impl.platform.LocalizationParityResourceTest' --tests 'ru.lazyhat.compukterkraft.impl.platform.WorkbenchBlockModelResourceTest' --console=plain`
Expected: PASS for both tests.

- [ ] **Step 2: Confirm final Russian localization contents**

```json
{
  "block.compukterkraft.computer_advanced": "Продвинутый компьютер",
  "block.compukterkraft.workbench": "Верстак",
  "item.compukterkraft.computer_advanced": "Продвинутый компьютер",
  "item.compukterkraft.workbench": "Верстак",
  "itemGroup.compukterkraft": "Compukter Kraft",
  "gui.compukterkraft.terminal.powered_off": "Компьютер выключен. Сначала включите его.",
  "gui.compukterkraft.terminal.connecting": "Подключение...",
  "gui.compukterkraft.tooltip.computer_id": "ID компьютера: %s",
  "gui.compukterkraft.tooltip.copy": "Скопировать в буфер обмена",
  "commands.compukterkraft.generic.yes": "Д",
  "commands.compukterkraft.generic.no": "Н",
  "commands.compukterkraft.dump.action": "Показать больше информации об этом компьютере"
}
```

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-04-17-russian-localization-design.md docs/superpowers/plans/2026-04-17-russian-localization.md modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/lang/ru_ru.json modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/platform/EnglishLocalizationResourceTest.kt
git commit -m "feat: add russian localization coverage"
```