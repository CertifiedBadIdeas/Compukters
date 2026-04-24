# Terminal Back Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the flat terminal item sprite with a simple custom item model that has distinct front, back, and side textures.

**Architecture:** Keep the asset intentionally small: a single thin cuboid item model with explicit face textures. Preserve the current front look, add a minimalist rear panel, and tune item transforms only if the first render shows obvious orientation or readability issues.

**Tech Stack:** Minecraft JSON item models, PNG pixel textures, Gradle runClient for manual visual verification.

---

### Task 1: Prepare terminal textures

**Files:**
- Create: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/textures/item/terminal_front.png`
- Create: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/textures/item/terminal_back.png`
- Create: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/textures/item/terminal_side.png`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/textures/item/terminal.png`

- [ ] **Step 1: Preserve the current front art as the source for the new front texture**

Copy the current 16x16 terminal face into `terminal_front.png` without changing its silhouette.

- [ ] **Step 2: Draw the new back texture**

Create a 16x16 `terminal_back.png` with:

```text
- dark outer casing
- lighter central rear cover
- two small screw pixels/clusters
- subtle bottom marking/logo line
```

- [ ] **Step 3: Draw the side texture**

Create a narrow-readability `terminal_side.png` that uses a darker casing tone with a slight highlight so the device reads as thin but solid.

- [ ] **Step 4: Keep or repurpose `terminal.png` intentionally**

Either keep `terminal.png` as a legacy placeholder if another reference still needs it, or update it to mirror the front texture so resource packs and accidental references still show a correct front face.

- [ ] **Step 5: Verify texture dimensions**

Run: `file modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/textures/item/terminal_front.png modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/textures/item/terminal_back.png modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/textures/item/terminal_side.png`

Expected: all files reported as 16 x 16 PNG images.

### Task 2: Replace the generated item model

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/models/item/terminal.json`

- [ ] **Step 1: Replace the generated model with a thin cuboid**

Use a hand-authored item model with one element and separate textures:

```json
{
  "textures": {
    "front": "compukterkraft:item/terminal_front",
    "back": "compukterkraft:item/terminal_back",
    "side": "compukterkraft:item/terminal_side"
  },
  "elements": [
    {
      "from": [2, 1, 7.5],
      "to": [14, 15, 8.5],
      "faces": {
        "north": { "texture": "#front" },
        "south": { "texture": "#back" },
        "east": { "texture": "#side" },
        "west": { "texture": "#side" },
        "up": { "texture": "#side" },
        "down": { "texture": "#side" }
      }
    }
  ]
}
```

- [ ] **Step 2: Add display transforms only if first render proves they are needed**

If the item looks rotated incorrectly or loses front/back readability, add explicit `display` transforms matching the chosen held orientation rather than guessing early.

- [ ] **Step 3: Validate JSON structure**

Run: `jq . modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/models/item/terminal.json >/dev/null`

Expected: command exits successfully with no output.

### Task 3: Visually verify the asset in-game

**Files:**
- Verify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/models/item/terminal.json`
- Verify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/textures/item/terminal_front.png`
- Verify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/textures/item/terminal_back.png`
- Verify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/assets/compukterkraft/textures/item/terminal_side.png`

- [ ] **Step 1: Launch a client for visual inspection**

Run: `./gradlew :modules:v1_21_1:v1_21_1-neoforge:runClient`

Expected: the client starts without asset-loading errors.

- [ ] **Step 2: Inspect GUI, held, and dropped views**

Check these cases manually:

```text
- inventory / hotbar readability
- held item front visibility
- dropped item shows distinct back side when rotated
```

- [ ] **Step 3: Adjust transforms or side tone only if inspection shows a concrete problem**

If the item looks too thick, too dark, or front/back are hard to distinguish, make the smallest correction needed and rerun the visual inspection.

- [ ] **Step 4: Record the asset change cleanly**

Run: `git status --short`

Expected: only the planned terminal model, textures, and plan/spec files are listed for this task.