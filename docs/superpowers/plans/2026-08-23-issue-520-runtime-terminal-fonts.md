# Runtime-selectable Terminal Fonts Implementation Plan

> Issue: [#520](https://github.com/CertifiedBadIdeas/Compukters/issues/520)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add persistent client-local runtime selection between Cozette 6x13, Dina 6x10, and ProggyTiny 6x10 in the terminal Screen UI.

**Architecture:** Generalize the existing deterministic BDF atlas generator, then expose the generated fonts through a fixed `TerminalFontProfile` catalog. A NeoForge client config stores the selected stable profile ID, while `TerminalScreen` owns only the current presentation profile and a title-row cycle button; terminal payloads and replica cells remain unchanged.

**Tech Stack:** Kotlin 2.3, Gradle build logic, BDF bitmap fonts, Minecraft 26.1 client GUI, NeoForge 26.1 `ModConfigSpec`, JUnit/Kotlin Test.

---

### Task 1: Generalize the deterministic BDF atlas generator

**Files:**
- Rename: `build-scripts/src/main/kotlin/CozetteFontAtlas.kt` to `build-scripts/src/main/kotlin/TerminalBitmapFontAtlas.kt`
- Rename: `build-scripts/src/test/kotlin/CozetteFontAtlasTest.kt` to `build-scripts/src/test/kotlin/TerminalBitmapFontAtlasTest.kt`

- [ ] **Step 1: Write a failing parameterized generator test**

Replace the Cozette-specific test setup with a small fixture and an explicit specification:

```kotlin
private val SPEC =
    TerminalBitmapFontSpec(
        displayName = "Fixture",
        resourceName = "fixture",
        coveragePropertyName = "FIXTURE_SUPPORTED_CODE_POINTS",
        sourceDescription = "fixture BDF",
        cellWidth = 6,
        cellHeight = 13,
        ascent = 10,
        descent = 3,
        replacementCodePoint = 0xFFFD,
        selectedCodePoints = listOf(0x20..0x7E, 0x0400..0x04FF, 0x2500..0x25FF, 0xFFFD..0xFFFD),
    )

@Test
fun `spec controls metrics resource names coverage and replacement`() {
    val generated = TerminalBitmapFontAtlas.generate(SPEC, FIXTURE.byteInputStream())

    assertEquals(6, generated.cellWidth)
    assertEquals(13, generated.cellHeight)
    assertEquals(10, generated.ascent)
    assertTrue(generated.fontJson.contains("compukters:font/terminal/fixture.png"))
    assertTrue(generated.coverageKotlin.contains("FIXTURE_SUPPORTED_CODE_POINTS"))
    assertTrue(generated.codePoints.binarySearch(0xFFFD) >= 0)
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
./gradlew-sandbox-dev-parallel :build-scripts:test --tests TerminalBitmapFontAtlasTest
```

Expected: compilation fails because `TerminalBitmapFontSpec` and `TerminalBitmapFontAtlas` do not exist.

- [ ] **Step 3: Introduce the generic specification and output model**

Replace the hard-coded Cozette constants with this public build-logic contract while retaining the existing strict BDF parser, bitmap projection, PNG encoding, and duplicate checks:

```kotlin
data class TerminalBitmapFontSpec(
    val displayName: String,
    val resourceName: String,
    val coveragePropertyName: String,
    val sourceDescription: String,
    val cellWidth: Int,
    val cellHeight: Int,
    val ascent: Int,
    val descent: Int,
    val replacementCodePoint: Int,
    val selectedCodePoints: List<IntRange>,
) {
    init {
        require(resourceName.matches(Regex("[a-z0-9_-]+")))
        require(coveragePropertyName.matches(Regex("[A-Z0-9_]+")))
        require(cellWidth > 0 && cellHeight == ascent + descent)
        require(ascent > 0 && descent >= 0)
    }

    fun selects(codePoint: Int): Boolean = selectedCodePoints.any { codePoint in it }
}

object TerminalBitmapFontAtlas {
    fun generate(spec: TerminalBitmapFontSpec, source: InputStream): GeneratedTerminalBitmapFont {
        val parsed = parse(source)
        require(parsed.ascent == spec.ascent)
        require(parsed.descent == spec.descent)
        val selected = parsed.glyphs.filter { spec.selects(it.encoding) }.sortedBy(BdfGlyph::encoding)
        require(selected.any { it.encoding == spec.replacementCodePoint })
        selected.forEach { glyph ->
            require(glyph.advanceX == spec.cellWidth && glyph.advanceY == 0)
        }
        return generateOutputs(spec, selected)
    }
}
```

Rename `GeneratedCozetteFont` to `GeneratedTerminalBitmapFont`. Make JSON texture paths, `height`, `ascent`, generated source comments, and the coverage property name come from `TerminalBitmapFontSpec`.

- [ ] **Step 4: Replace the two Cozette-only Gradle task types**

Create reusable `GenerateTerminalBitmapFont` and `VerifyTerminalBitmapFont` task types. Add scalar Gradle properties for every specification field and build the spec in one shared helper:

```kotlin
private fun terminalFontSpec() =
    TerminalBitmapFontSpec(
        displayName.get(),
        resourceName.get(),
        coveragePropertyName.get(),
        sourceDescription.get(),
        cellWidth.get(),
        cellHeight.get(),
        ascent.get(),
        descent.get(),
        replacementCodePoint.get(),
        selectedRanges.get().map { encoded ->
            val (first, last) = encoded.split("..").map(String::toInt)
            first..last
        },
    )
```

Both tasks must call the same generator; verification compares all four committed outputs byte-for-byte and reports the concrete regeneration task name.

- [ ] **Step 5: Run build-logic tests**

Run:

```bash
./gradlew-sandbox-dev-parallel :build-scripts:test
```

Expected: all build-logic tests pass and two generations from one fixture remain byte-identical.

- [ ] **Step 6: Commit the generator refactor**

```bash
git add build-scripts/src/main/kotlin/TerminalBitmapFontAtlas.kt build-scripts/src/test/kotlin/TerminalBitmapFontAtlasTest.kt
git commit -m "refactor(font): generalize bitmap atlas generation"
```

### Task 2: Pin Dina and ProggyTiny and generate packaged resources

**Files:**
- Create: `tools/fonts/dina/v2.92/Dina_r400-6.bdf`
- Create: `tools/fonts/dina/v2.92/LICENSE`
- Create: `tools/fonts/dina/v2.92/PROVENANCE.md`
- Create: `tools/fonts/proggy/139ec08a/ProggyTiny.pcf.gz`
- Create: `tools/fonts/proggy/139ec08a/ProggyTiny.bdf`
- Create: `tools/fonts/proggy/139ec08a/LICENSE`
- Create: `tools/fonts/proggy/139ec08a/PROVENANCE.md`
- Create: `modules/v26_1/v26_1-neoforge/src/main/resources/assets/compukters/font/terminal/dina.json`
- Create: `modules/v26_1/v26_1-neoforge/src/main/resources/assets/compukters/font/terminal/dina-codepoints.txt`
- Create: `modules/v26_1/v26_1-neoforge/src/main/resources/assets/compukters/textures/font/terminal/dina.png`
- Create: `modules/v26_1/v26_1-neoforge/src/main/resources/assets/compukters/font/terminal/proggy_tiny.json`
- Create: `modules/v26_1/v26_1-neoforge/src/main/resources/assets/compukters/font/terminal/proggy_tiny-codepoints.txt`
- Create: `modules/v26_1/v26_1-neoforge/src/main/resources/assets/compukters/textures/font/terminal/proggy_tiny.png`
- Create: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/terminal/DinaFontCoverage.kt`
- Create: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/terminal/ProggyTinyFontCoverage.kt`
- Create: `modules/v26_1/v26_1-neoforge/src/main/resources/META-INF/licenses/Dina-LICENSE.txt`
- Create: `modules/v26_1/v26_1-neoforge/src/main/resources/META-INF/licenses/Dina-PROVENANCE.txt`
- Create: `modules/v26_1/v26_1-neoforge/src/main/resources/META-INF/licenses/Proggy-MIT.txt`
- Create: `modules/v26_1/v26_1-neoforge/src/main/resources/META-INF/licenses/Proggy-PROVENANCE.txt`
- Modify: `modules/v26_1/v26_1-neoforge/build.gradle.kts`
- Replace: `modules/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/terminal/CozetteFontResourceTest.kt` with `TerminalFontResourceTest.kt`

- [ ] **Step 1: Pin exact upstream inputs and provenance**

Copy `BDF/Dina_r400-6.bdf` and `LICENSE` from the official Dina v2.92 archive. Record archive SHA-256 `1f51bba53f75a64d2d8bd037e8e0f84b6f8064e50a72ee954033bede173508cf` and BDF SHA-256 `0efe660581b38b8025a46401d2c919c7e654fc21c81979e8d31e714c414deba1`.

Pin `ProggyOriginal/ProggyTiny.pcf.gz` from commit `139ec08a38096161291792313ef5803fc4f0e37b`; record upstream SHA-256 `a8beed341cfa79272b80c48d3237c417ff7b155468b95a634a70ba918d6d503a`. Convert the strike once with pcf2bdf 1.07 at commit `4e80d7fa069b4be08ec4e23e4d5086ef046e86aa` without scaling:

```bash
pcf2bdf -o ProggyTiny.bdf ProggyTiny.pcf.gz
```

Verify that the result retains `6x10`, ascent `8`, descent `2`, and a six-pixel advance, then record the source hash, converter version, and command in `PROVENANCE.md`. Normal Gradle builds consume only the committed BDF.

- [ ] **Step 2: Write failing resource-contract tests**

Use one data table to check all profiles:

```kotlin
private data class ExpectedFont(
    val id: String,
    val width: Int,
    val height: Int,
    val ascent: Int,
    val replacement: Int,
)

private val fonts =
    listOf(
        ExpectedFont("cozette", 6, 13, 10, 0xFFFD),
        ExpectedFont("dina", 6, 10, 8, '?'.code),
        ExpectedFont("proggy_tiny", 6, 10, 8, '?'.code),
    )

@Test
fun `all committed terminal resources match their profiles`() {
    fonts.forEach { expected ->
        val json = runtimeResource("/assets/compukters/font/terminal/${expected.id}.json").reader().readText()
        val image = runtimeResource("/assets/compukters/textures/font/terminal/${expected.id}.png").use(ImageIO::read)
        assertTrue(json.contains("\"height\": ${expected.height}"))
        assertTrue(json.contains("\"ascent\": ${expected.ascent}"))
        assertEquals(0, image.width % expected.width)
        assertEquals(0, image.height % expected.height)
    }
}
```

- [ ] **Step 3: Run the resource test and verify it fails**

Run:

```bash
./gradlew-sandbox-dev-parallel :v26_1-neoforge:test --tests '*TerminalFontResourceTest'
```

Expected: failure because Dina and ProggyTiny runtime resources are absent.

- [ ] **Step 4: Register three generator/verification specifications**

Add a helper in the module build script:

```kotlin
fun registerTerminalFont(
    id: String,
    taskStem: String,
    displayName: String,
    source: RegularFile,
    cellHeight: Int,
    ascent: Int,
    replacement: Int,
    ranges: List<String>,
) {
    val fontJson = layout.projectDirectory.file("src/main/resources/assets/compukters/font/terminal/$id.json")
    val atlas = layout.projectDirectory.file("src/main/resources/assets/compukters/textures/font/terminal/$id.png")
    val manifest = layout.projectDirectory.file("src/main/resources/assets/compukters/font/terminal/$id-codepoints.txt")
    val coverage = layout.projectDirectory.file(
        "src/main/kotlin/ru/lazyhat/compukters/impl/terminal/${taskStem}FontCoverage.kt",
    )
    val generate = tasks.register<GenerateTerminalBitmapFont>("generate${taskStem}TerminalFont") {
        bdfFile.set(source)
        resourceName.set(id)
        this.displayName.set(displayName)
        coveragePropertyName.set("${id.uppercase()}_SUPPORTED_CODE_POINTS")
        sourceDescription.set(displayName)
        cellWidth.set(6)
        this.cellHeight.set(cellHeight)
        this.ascent.set(ascent)
        descent.set(cellHeight - ascent)
        replacementCodePoint.set(replacement)
        selectedRanges.set(ranges)
        fontJsonFile.set(fontJson)
        atlasPngFile.set(atlas)
        manifestFile.set(manifest)
        coverageKotlinFile.set(coverage)
    }
    val verify = tasks.register<VerifyTerminalBitmapFont>("verify${taskStem}TerminalFont") {
        regenerationTaskName.set(generate.name)
        bdfFile.set(source)
        resourceName.set(id)
        this.displayName.set(displayName)
        coveragePropertyName.set("${id.uppercase()}_SUPPORTED_CODE_POINTS")
        sourceDescription.set(displayName)
        cellWidth.set(6)
        this.cellHeight.set(cellHeight)
        this.ascent.set(ascent)
        descent.set(cellHeight - ascent)
        replacementCodePoint.set(replacement)
        selectedRanges.set(ranges)
        fontJsonFile.set(fontJson)
        atlasPngFile.set(atlas)
        manifestFile.set(manifest)
        coverageKotlinFile.set(coverage)
        mustRunAfter(generate)
    }
    tasks.named("check") { dependsOn(verify) }
}
```

Register Cozette with its existing curated ranges and U+FFFD, then Dina and ProggyTiny with printable ASCII plus U+00A0..U+00FF and `?`. Wire every verification task into `check`.

- [ ] **Step 5: Generate and validate all committed resources**

Run:

```bash
./gradlew-sandbox-dev-parallel :v26_1-neoforge:generateCozetteTerminalFont :v26_1-neoforge:generateDinaTerminalFont :v26_1-neoforge:generateProggyTinyTerminalFont
./gradlew-sandbox-dev-parallel :v26_1-neoforge:test --tests '*TerminalFontResourceTest'
```

Expected: resources are generated deterministically and the resource-contract test passes.

- [ ] **Step 6: Commit pinned inputs and generated assets**

```bash
git add tools/fonts build-scripts modules/v26_1/v26_1-neoforge
git commit -m "feat(font): package Dina and ProggyTiny"
```

### Task 3: Add the font catalog and persistent client preference

**Files:**
- Modify: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/terminal/TerminalFontProfile.kt`
- Create: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/config/CompuktersClientConfig.kt`
- Modify: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/CompuktersMod.kt`
- Modify: `modules/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/terminal/TerminalFontProfileTest.kt`
- Create: `modules/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/config/CompuktersClientConfigTest.kt`

- [ ] **Step 1: Write failing catalog and selection tests**

```kotlin
@Test
fun `catalog resolves IDs and cycles in presentation order`() {
    assertEquals(TerminalFontProfile.COZETTE, TerminalFontProfile.fromId("missing"))
    assertEquals(TerminalFontProfile.DINA, TerminalFontProfile.COZETTE.next())
    assertEquals(TerminalFontProfile.PROGGY_TINY, TerminalFontProfile.DINA.next())
    assertEquals(TerminalFontProfile.COZETTE, TerminalFontProfile.PROGGY_TINY.next())
}

@Test
fun `compact profiles use honest native fallback`() {
    listOf(TerminalFontProfile.DINA, TerminalFontProfile.PROGGY_TINY).forEach { profile ->
        assertEquals(6, profile.cellWidth)
        assertEquals(10, profile.cellHeight)
        assertEquals(8, profile.ascent)
        assertEquals('?'.code, profile.renderCodePoint('Ж'.code))
    }
}
```

Test that the config specification defaults `terminal.font` to `cozette` and accepts exactly the catalog IDs through its value validator.

- [ ] **Step 2: Run focused tests and verify they fail**

Run:

```bash
./gradlew-sandbox-dev-parallel :v26_1-neoforge:test --tests '*TerminalFontProfileTest' --tests '*CompuktersClientConfigTest'
```

Expected: compilation fails because the catalog and client config do not exist.

- [ ] **Step 3: Implement the fixed profile catalog**

Expose immutable instances and stable operations:

```kotlin
companion object {
    val COZETTE = terminalProfile("cozette", "Cozette", 6, 13, 10, COZETTE_SUPPORTED_CODE_POINTS, 0xFFFD)
    val DINA = terminalProfile("dina", "Dina", 6, 10, 8, DINA_SUPPORTED_CODE_POINTS, '?'.code)
    val PROGGY_TINY = terminalProfile(
        "proggy_tiny", "ProggyTiny", 6, 10, 8, PROGGY_TINY_SUPPORTED_CODE_POINTS, '?'.code,
    )
    val ALL = listOf(COZETTE, DINA, PROGGY_TINY)
    val DEFAULT = COZETTE

    fun fromId(id: String?): TerminalFontProfile = ALL.firstOrNull { it.id == id } ?: DEFAULT
}

fun next(): TerminalFontProfile = ALL[(ALL.indexOf(this) + 1) % ALL.size]
```

The helper constructs `FontDescription.Resource(Identifier.fromNamespaceAndPath("compukters", "terminal/$id"))` and retains all existing metric/coverage invariants.

- [ ] **Step 4: Register and expose the NeoForge client config**

```kotlin
object CompuktersClientConfig {
    private val builder = ModConfigSpec.Builder()
    internal val terminalFontId =
        builder
            .comment("Font used by the local terminal screen")
            .define("terminal.font", TerminalFontProfile.DEFAULT.id) { value ->
                value is String && TerminalFontProfile.ALL.any { it.id == value }
            }
    val SPEC: ModConfigSpec = builder.build()

    fun selectedFont(): TerminalFontProfile = TerminalFontProfile.fromId(terminalFontId.get())

    fun selectFont(profile: TerminalFontProfile) {
        terminalFontId.set(profile.id)
        terminalFontId.save()
    }
}
```

Inject `ModContainer` into `CompuktersMod` and call:

```kotlin
modContainer.registerConfig(ModConfig.Type.CLIENT, CompuktersClientConfig.SPEC)
```

- [ ] **Step 5: Run catalog/config tests**

Run:

```bash
./gradlew-sandbox-dev-parallel :v26_1-neoforge:test --tests '*TerminalFontProfileTest' --tests '*CompuktersClientConfigTest'
```

Expected: all profile and config contract tests pass.

- [ ] **Step 6: Commit catalog and client config**

```bash
git add modules/v26_1/v26_1-neoforge/src/main/kotlin modules/v26_1/v26_1-neoforge/src/test/kotlin
git commit -m "feat(terminal): persist client font selection"
```

### Task 4: Add the runtime font selector to TerminalScreen

**Files:**
- Modify: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/terminal/TerminalRenderGeometry.kt`
- Modify: `modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/terminal/TerminalScreen.kt`
- Modify: `modules/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/terminal/TerminalRenderGeometryTest.kt`

- [ ] **Step 1: Write failing geometry tests for compact profiles and selector placement**

```kotlin
@Test
fun `compact font reduces panel height without changing logical grid`() {
    val cozette = TerminalRenderGeometry(640, 360, TerminalFontProfile.COZETTE)
    val dina = TerminalRenderGeometry(640, 360, TerminalFontProfile.DINA)

    assertEquals(cozette.columns, dina.columns)
    assertEquals(cozette.rows, dina.rows)
    assertEquals(57, cozette.panelHeight - dina.panelHeight)
    assertTrue(dina.fontButton.left >= dina.panel.left)
    assertTrue(dina.fontButton.right <= dina.panel.right)
}
```

- [ ] **Step 2: Run the geometry test and verify it fails**

Run:

```bash
./gradlew-sandbox-dev-parallel :v26_1-neoforge:test --tests '*TerminalRenderGeometryTest'
```

Expected: compilation fails because `fontButton` is absent.

- [ ] **Step 3: Add deterministic title-row button geometry**

Add `FONT_BUTTON_WIDTH = 84`, `FONT_BUTTON_HEIGHT = 14`, and expose:

```kotlin
val fontButton =
    TerminalRect(
        panel.right - PANEL_PADDING - FONT_BUTTON_WIDTH,
        panel.top + (TITLE_HEIGHT - FONT_BUTTON_HEIGHT) / 2,
        panel.right - PANEL_PADDING,
        panel.top + (TITLE_HEIGHT - FONT_BUTTON_HEIGHT) / 2 + FONT_BUTTON_HEIGHT,
    )
```

- [ ] **Step 4: Make TerminalScreen profile state mutable and add the widget**

Replace constructor injection of a fixed profile with client preference initialization:

```kotlin
private var fontProfile = CompuktersClientConfig.selectedFont()
private lateinit var fontButton: Button

override fun init() {
    super.init()
    val geometry = TerminalRenderGeometry(width, height, fontProfile)
    fontButton =
        addRenderableWidget(
            Button.builder(fontButtonLabel()) { cycleFont() }
                .bounds(
                    geometry.fontButton.left,
                    geometry.fontButton.top,
                    geometry.fontButton.width,
                    geometry.fontButton.height,
                )
                .build(),
        )
}

private fun cycleFont() {
    fontProfile = fontProfile.next()
    CompuktersClientConfig.selectFont(fontProfile)
    fontButton.message = fontButtonLabel()
    positionFontButton()
}

private fun fontButtonLabel(): Component = Component.literal("Font: ${fontProfile.displayName}")
```

`positionFontButton()` recomputes geometry and applies `x`, `y`, width, and height to the existing widget. Screen resize recreates widgets through `init()`. Rendering continues to call `fontProfile.renderCodePoint(cell.codePoint)` and never mutates `TerminalReplica`.

- [ ] **Step 5: Run terminal unit tests**

Run:

```bash
./gradlew-sandbox-dev-parallel :v26_1-neoforge:test --tests 'ru.lazyhat.compukters.impl.terminal.*'
```

Expected: all terminal tests pass, including the 57-pixel panel-height reduction for 19 rows.

- [ ] **Step 6: Commit the runtime selector**

```bash
git add modules/v26_1/v26_1-neoforge/src/main/kotlin/ru/lazyhat/compukters/impl/terminal modules/v26_1/v26_1-neoforge/src/test/kotlin/ru/lazyhat/compukters/impl/terminal
git commit -m "feat(terminal): switch fonts at runtime"
```

### Task 5: Verify packaged licenses, resources, persistence, and the complete build

**Files:**
- Modify: `modules/v26_1/v26_1-neoforge/build.gradle.kts`
- Modify: `docs/superpowers/plans/2026-08-23-issue-520-runtime-terminal-fonts.md`

- [ ] **Step 1: Extend production-JAR assertions**

Add the Dina and ProggyTiny JSON, manifests, atlases, and four license/provenance documents to the existing required-entry list in `verifyPackagedCompukterFfi`. Keep the assertions for absence of the legacy terminal atlas and Spleen attribution.

- [ ] **Step 2: Run formatting and fast verification**

Run:

```bash
./gradlew-sandbox-dev-parallel formatKotlin verifyLocalFast :v26_1-neoforge:verifyPackagedCompukterFfi --rerun-tasks
```

Expected: formatting completes, all fast checks pass, and the production JAR contains all three terminal fonts and their attributions.

- [ ] **Step 3: Run full verification**

Run:

```bash
./gradlew-sandbox-dev-parallel verifyLocalFull --rerun-tasks
```

Expected: the complete Kotlin, Rust, integration, packaging, formatting, and static-analysis suite passes.

- [ ] **Step 4: Perform the in-game smoke check**

Run the NeoForge development client, open one computer, and verify:

1. The button cycles `Cozette -> Dina -> ProggyTiny -> Cozette` without closing the screen.
2. Dina and ProggyTiny reduce the grid height by 57 pixels while preserving all 51x19 cells.
3. Unsupported Cyrillic renders as `?` in Dina/ProggyTiny and reappears intact after selecting Cozette.
4. Terminal typing and Enter still reach the shell; clicking the font button does not type into it.
5. Closing/reopening the screen and restarting the client retain the selected font.

- [ ] **Step 5: Commit final packaging assertions and plan completion**

```bash
git add modules/v26_1/v26_1-neoforge/build.gradle.kts docs/superpowers/plans/2026-08-23-issue-520-runtime-terminal-fonts.md
git commit -m "test(font): verify packaged terminal profiles"
```
