# English Localization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore an `en_us.json` file for Compukter Kraft containing only translation keys that are currently used by the mod.

**Architecture:** Recover the last deleted English localization file from git history, filter it down to current keys used by the mod, and store the result in the NeoForge resource pack path. Protect the restored file with a focused resource test that verifies the required keys exist on the classpath.

**Tech Stack:** Kotlin tests, Gradle, NeoForge resources, JSON localization assets

---

### Task 1: Restore The Minimal English Lang File

**Files:**
- Create: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/lang/en_us.json`
- Reference: `modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/ModRegistry.kt`
- Reference: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerTerminalScreen.kt`
- Reference: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/screen/WorkbenchEditorScreen.kt`
- Reference: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/text/ChatHelpers.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun englishLocalizationContainsRequiredCurrentModKeys() {
    val json = checkNotNull(javaClass.classLoader.getResource("assets/compukterkraft/lang/en_us.json")).readText()

    val requiredKeys = listOf(
        "block.compukterkraft.computer_advanced",
        "block.compukterkraft.workbench",
        "item.compukterkraft.computer_advanced",
        "item.compukterkraft.workbench",
        "itemGroup.compukterkraft",
        "gui.compukterkraft.terminal.powered_off",
        "gui.compukterkraft.terminal.connecting",
        "gui.compukterkraft.tooltip.computer_id",
        "gui.compukterkraft.tooltip.copy",
        "commands.compukterkraft.generic.yes",
        "commands.compukterkraft.generic.no",
        "commands.compukterkraft.dump.action",
    )

    requiredKeys.forEach { key ->
        assertTrue(json.contains("\"$key\""), "Expected en_us.json to contain $key")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :v1_21_1-neoforge:test --tests 'ru.lazyhat.compukterkraft.impl.platform.EnglishLocalizationResourceTest' --console=plain`
Expected: FAIL because `en_us.json` does not exist yet.

- [ ] **Step 3: Write minimal implementation**

```json
{
  "block.compukterkraft.computer_advanced": "Advanced Computer",
  "block.compukterkraft.workbench": "Workbench",
  "item.compukterkraft.computer_advanced": "Advanced Computer",
  "item.compukterkraft.workbench": "Workbench",
  "itemGroup.compukterkraft": "Compukter Kraft",
  "gui.compukterkraft.terminal.powered_off": "Computer is off. Turn it on first.",
  "gui.compukterkraft.terminal.connecting": "Connecting...",
    "gui.compukterkraft.tooltip.computer_id": "Computer ID: %s",
  "gui.compukterkraft.tooltip.copy": "Copy to clipboard",
  "commands.compukterkraft.generic.yes": "Y",
  "commands.compukterkraft.generic.no": "N",
  "commands.compukterkraft.dump.action": "View more info about this computer"
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :v1_21_1-neoforge:test --tests 'ru.lazyhat.compukterkraft.impl.platform.EnglishLocalizationResourceTest' --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/lang/en_us.json modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/platform/EnglishLocalizationResourceTest.kt
git commit -m "feat: restore english localization"
```

### Task 2: Add Focused Resource Coverage

**Files:**
- Create: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/platform/EnglishLocalizationResourceTest.kt`
- Reference: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/platform/WorkbenchBlockModelResourceTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
class EnglishLocalizationResourceTest {
    @Test
    fun englishLocalizationContainsRequiredCurrentModKeys() {
        val json = checkNotNull(javaClass.classLoader.getResource("assets/compukterkraft/lang/en_us.json")).readText()

        val requiredKeys = listOf(
            "block.compukterkraft.computer_advanced",
            "block.compukterkraft.workbench",
            "item.compukterkraft.computer_advanced",
            "item.compukterkraft.workbench",
            "itemGroup.compukterkraft",
            "gui.compukterkraft.terminal.powered_off",
            "gui.compukterkraft.terminal.connecting",
            "gui.compukterkraft.tooltip.computer_id",
            "gui.compukterkraft.tooltip.copy",
            "commands.compukterkraft.generic.yes",
            "commands.compukterkraft.generic.no",
            "commands.compukterkraft.dump.action",
        )

        requiredKeys.forEach { key ->
            assertTrue(json.contains("\"$key\""), "Expected en_us.json to contain $key")
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :v1_21_1-neoforge:test --tests 'ru.lazyhat.compukterkraft.impl.platform.EnglishLocalizationResourceTest' --console=plain`
Expected: FAIL because the resource or one or more required keys are missing.

- [ ] **Step 3: Write minimal implementation**

```kotlin
import kotlin.test.Test
import kotlin.test.assertTrue

class EnglishLocalizationResourceTest {
    @Test
    fun englishLocalizationContainsRequiredCurrentModKeys() {
        val json = checkNotNull(javaClass.classLoader.getResource("assets/compukterkraft/lang/en_us.json")).readText()

        val requiredKeys = listOf(
            "block.compukterkraft.computer_advanced",
            "block.compukterkraft.workbench",
            "item.compukterkraft.computer_advanced",
            "item.compukterkraft.workbench",
            "itemGroup.compukterkraft",
            "gui.compukterkraft.terminal.powered_off",
            "gui.compukterkraft.terminal.connecting",
            "gui.compukterkraft.tooltip.computer_id",
            "gui.compukterkraft.tooltip.copy",
            "commands.compukterkraft.generic.yes",
            "commands.compukterkraft.generic.no",
            "commands.compukterkraft.dump.action",
        )

        requiredKeys.forEach { key ->
            assertTrue(json.contains("\"$key\""), "Expected en_us.json to contain $key")
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :v1_21_1-neoforge:test --tests 'ru.lazyhat.compukterkraft.impl.platform.EnglishLocalizationResourceTest' --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/platform/EnglishLocalizationResourceTest.kt
git commit -m "test: cover english localization resource"
```

### Task 3: Run Final Targeted Verification

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/lang/en_us.json`
- Test: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/platform/EnglishLocalizationResourceTest.kt`

- [ ] **Step 1: Run focused verification suite**

Run: `./gradlew :v1_21_1-neoforge:test --tests 'ru.lazyhat.compukterkraft.impl.platform.WorkbenchBlockModelResourceTest' --tests 'ru.lazyhat.compukterkraft.impl.platform.EnglishLocalizationResourceTest' --console=plain`
Expected: PASS for both tests.

- [ ] **Step 2: Confirm final file contents stay in scope**

```json
{
  "block.compukterkraft.computer_advanced": "Advanced Computer",
  "block.compukterkraft.workbench": "Workbench",
  "item.compukterkraft.computer_advanced": "Advanced Computer",
  "item.compukterkraft.workbench": "Workbench",
  "itemGroup.compukterkraft": "Compukter Kraft",
  "gui.compukterkraft.terminal.powered_off": "Computer is off. Turn it on first.",
  "gui.compukterkraft.terminal.connecting": "Connecting...",
    "gui.compukterkraft.tooltip.computer_id": "Computer ID: %s",
  "gui.compukterkraft.tooltip.copy": "Copy to clipboard",
  "commands.compukterkraft.generic.yes": "Y",
  "commands.compukterkraft.generic.no": "N",
  "commands.compukterkraft.dump.action": "View more info about this computer"
}
```

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-04-17-english-localization-design.md docs/superpowers/plans/2026-04-17-english-localization.md modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/lang/en_us.json modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/platform/EnglishLocalizationResourceTest.kt
git commit -m "feat: restore current english localization"
```