## Plan: Computer Terminal UI Improvements (4 issues)

**TL;DR**: Fix 4 terminal UI issues: (1) disable terminal access when computer is off, (2) enable cursor blink during `readLine`, (3) forward mouse scroll to VM, (4) clip editor lines to bounds. All changes span 4 files.

---

### Issue 1: Disable terminal access when computer stops

**Root cause**: `data` ContainerData slot 0 syncs `isOn` from server, but client never reads it.

**Steps**:
1. Add `isComputerOn` property to `AbstractComputerMenu` — reads `data.get(0) == 1`
2. In `renderBg()` — when TERMINAL mode and computer is off, render black screen instead of the normal terminal
3. In `containerTick()` — auto-unfocus `terminalInput` when `!menu.isComputerOn`
4. In `keyPressed()` / `charTyped()` / `mouseClicked()` / `mouseScrolled()` — block terminal input forwarding when off

**Files**: `mod/src/main/kotlin/ck/mod/menu/AbstractComputerMenu.kt`, `mod/src/main/kotlin/ck/mod/gui/screen/ComputerWorkbenchScreen.kt`

---

### Issue 2: Cursor blink during readLine

**Root cause**: `ScreenBuffer.setCursorBlink()` exists but is **never called** anywhere. `cursorBlink` defaults to `false`. The renderer already checks `snapshot.cursorBlink && FrameInfo.globalCursorBlink`.

**Steps**:
1. In `VmTerminalApi.readLine()` — wrap the `TerminalLineReader` call with `screenBuffer.setCursorBlink(true)` before / `false` after (try/finally)

No other files need changes — the rendering pipeline already handles `cursorBlink` correctly.

**Files**: `mod/src/main/kotlin/ck/mod/computer/vm/VmTerminalApi.kt`

---

### Issue 3: Terminal mouse scroll forwarded to VM

**Root cause**: `mouseScrolled()` in `ComputerWorkbenchScreen` only handles EDITOR mode. In TERMINAL mode, scroll falls through to `super` (no-op).

**Steps**:
1. Add `mouseScrolled(bounds, mouseX, mouseY, delta)` to `WorkbenchTerminalInputController` — converts pixel coords to terminal cell coords (`cellX = (mouseX - bounds.x) / FONT_WIDTH + 1`) and sends `MouseInputEvent.Scroll(direction, cellX, cellY)` to the computer
2. In `ComputerWorkbenchScreen.mouseScrolled()` — add TERMINAL mode handling before EDITOR check

**Files**: `mod/src/main/kotlin/ck/mod/gui/WorkbenchTerminalInputController.kt`, `mod/src/main/kotlin/ck/mod/gui/screen/ComputerWorkbenchScreen.kt`

---

### Issue 4: IDE editor lines clipped to UI bounds

**Root cause**: `renderEditor()` uses `graphics.drawString()` without scissoring. Long lines render past the editor's right edge.

**Steps**:
1. In `renderEditor()` — wrap rendering with `graphics.enableScissor(editorBounds.x, editorBounds.y, editorBounds.right, editorBounds.bottom)` ... `graphics.disableScissor()`. Editor bounds are already computed as `UiRect(leftPos + 136, topPos + 34, imageWidth - 144, imageHeight - 66)` in `WorkbenchLayoutModel`

**Files**: `mod/src/main/kotlin/ck/mod/gui/screen/ComputerWorkbenchScreen.kt`

---

### All files to modify

| File | Changes |
|------|---------|
| `AbstractComputerMenu.kt` | Add `isComputerOn` property |
| `ComputerWorkbenchScreen.kt` | Off-state rendering/input blocking, scroll forwarding, editor scissoring |
| `VmTerminalApi.kt` | `setCursorBlink` in readLine |
| `WorkbenchTerminalInputController.kt` | Add `mouseScrolled` method |

### Verification
1. **Issue 1**: Boot → turn off → black screen + input blocked → turn on → works
2. **Issue 2**: Program calls `readLine()` → cursor blinks → after input → stops
3. **Issue 3**: Scroll mouse in terminal → program receives `mouse_scroll` events
4. **Issue 4**: Long lines in IDE → text clipped at editor right edge

### Decisions
- Off screen: plain black (no overlay text) — per user choice
- Cursor: blinks only during `readLine`, not always when focused — per user choice
- Scroll: forwarded to VM as `MouseInputEvent.Scroll`, no scrollback buffer — per user choice
