---
layout: default
title: Text display
description: Show program-written text on a display block in the world.
permalink: /DISPLAY/
---

# Text display

The base Compukters mod includes a one-block **Text Display** on both supported Minecraft versions. Its front face
shows a 20-column, 10-row text grid. The grid is independent of the computer's terminal and only accepts program
output; clicking the display does not send input to the computer.

Place a display next to a computer or connect it through loaded Peripheral Cables. For a cable-connected display, use
the Peripheral Configurator to give it a unique name in that cable network. Sides such as `front` and `left` are
relative to the computer's front face.

```kotlin
import compukter.display.Display

fun main() {
    val screen = Display.open("panel")
    screen.clear()
    screen.writeAt(0, 0, "Warehouse")
    screen.writeAt(0, 2, "Iron: 128")
}
```

For an adjacent display, use `Display.front.open()`, `Display.back.open()`, or another side accessor. Opening a display
returns a handle bound to that exact block. Removing, replacing, unloading, or disconnecting it invalidates the handle;
reconnecting does not retarget an old handle.

`writeAt(x, y, text)` starts at zero-based coordinates. Text must fit within one row; an out-of-range coordinate or a
write crossing the right edge fails instead of being clipped. Each write is limited to 256 UTF-8 bytes, and control
characters and line breaks are rejected. The display renders unsupported font glyphs as replacement characters.

The first computer to write or clear a display holds its output lease. Another computer cannot overwrite it until the
writer stops or loses its connection. The display clears automatically when its writer shuts down, halts, reboots,
disappears, or loses the cable path, including while the program is idle. Screen contents are not stored in world NBT;
reloading the block starts with a blank screen. Changed text is sent to nearby clients at most once per server tick.
