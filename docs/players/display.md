---
layout: default
title: Display
description: Draw persistent RGB graphics and text on a single or composite display.
permalink: /DISPLAY/
section: players
---

# Display

A display keeps its RGB image and resolution in the world. Stopping a program, rebooting or unloading its computer,
disconnecting a network, unloading display chunks and restarting the server preserve the last published image.
Programs clear it explicitly. Clicking a display does not send keyboard input.

Place a display next to a computer or bind it to the same peripheral network with the Peripheral Configurator.
Give the screen a name in the configurator's naming mode. Sides such as `front` and `left` are relative to the computer.

```kotlin
import compukter.display.Colors
import compukter.display.DisplayMode
import compukter.display.GraphicalDisplay
import compukter.display.frame
import compukter.display.rgb

fun main() {
    val screen = GraphicalDisplay.named("panel")
    screen.setMode(DisplayMode.LOW)
    screen.frame {
        clear(rgb(12, 18, 28))
        fillRect(2, 2, width - 4, height - 4, rgb(30, 50, 80))
        text(3, 3, "Ready", Colors.WHITE)
        pixel(0, 0, Colors.RED)
    }
}
```

## Composite screens

Cycle the Peripheral Configurator to **Display assembly** by using it in the air. Click two display blocks to select
opposite corners of a rectangle, at most 8 by 8 blocks. All panels must face the same direction and occupy one plane.
The whole rectangle must be loaded while assembling. Empty positions inside it are valid holes, including at creation.
Assembly creates a new blank canvas from standalone panels; an existing composite screen must not overlap the selection.

The selected screen remains in the configurator. Place another display in a hole and click it to join that screen.
Shift-click a panel to select its existing screen; shift-use the configurator in the air to clear the selection.
Particles show the selected rectangle.

A composite screen has one name, one resolution, one image and one writer lease. Reaching any loaded member reaches
the screen; typed discovery lists it once. Coordinates start at the top left when viewing its front, increase rightward
and downward, and include holes. Breaking a panel preserves geometry and pixels behind the hole. A new panel joined
there shows those pixels. Breaking the last panel deletes the canvas, name and resolution. Unloading a chunk does not.

## Resolution and drawing

Only a program changes resolution. The last applied mode is saved; changing mode clears the image to black, while
selecting the current mode preserves it. `width`, `height` and `mode` describe the canvas, including its holes.

| Mode | Pixels per block |
| --- | --- |
| `ULTRA_LOW` | 16 × 16 |
| `LOW` | 32 × 32 |
| `NORMAL` (new screen default) | 64 × 64 |
| `HIGH` | 128 × 128 |

Colors are integers from `0x000000` to `0xFFFFFF`, encoded as `0xRRGGBB`. `rgb(red, green, blue)` accepts channels from
0 to 255. `Colors` includes black, white, red, green, blue, yellow, cyan and magenta. There is no alpha channel.

- `clear(color = Colors.BLACK)` fills the canvas.
- `pixel(x, y, color)` and `line(x0, y0, x1, y1, color)` draw pixels and lines.
- `rect(x, y, width, height, color)` draws a border; `fillRect(...)` fills a rectangle.
- `text(x, y, text, color = Colors.WHITE, scale = 1)` draws monochrome JetBrains Mono glyphs in 6 × 12 pixel cells.
  Scale is 1–8; newline advances one line. Unsupported glyphs use a replacement. Limits are 256 code points and
  1024 UTF-8 bytes per call.
- `image(x, y, width, height, pixels)` draws row-major `IntArray` RGB pixels. Dimensions are 1–1024 and the image
  contains at most 65,536 pixels.

Graphics are clipped at canvas boundaries. Rectangle sizes cannot be negative. Drawing publishes immediately;
there is no required `present()` call. Server work and network transfer are bounded, so large updates can be delayed.
Clients keep the previous image until all loaded panels have received a complete publication.

## Optional frames and ownership

Import `compukter.display.frame` to group drawing with `screen.frame { ... }`. The changes remain private until the
block returns, then publish together. An exception discards them. Frames cannot nest, and another cooperative task
cannot access that screen while its frame is open. A program may hold at most 262,144 pixels in open frames combined;
choose a lower density for a large screen. A frame also has a cumulative drawing-work limit.

Hibernation preserves a private frame for the suspended program without publishing it. Restarting or terminating
that program discards the private frame and leaves the last published image. A normal program restart acquires a new
lease and sees the saved resolution.

The first program to draw holds the exclusive writer lease. Other programs cannot overwrite the screen until it
finishes or disconnects. Discovery alone does not claim a lease. `GraphicalDisplay` supports the standard typed
`named`, `at`, `first`, `all` and `filter` queries, with `OrNull` forms for optional selection. Missing strict queries
throw; ambiguous names are errors. A handle belongs to that logical canvas. Losing every reachable loaded panel makes
it stale; replacing or reconnecting a device does not revive a stale handle.

## Existing text programs

`TextDisplay` remains available with its 20-column, 10-row grid API:

```kotlin
import compukter.display.TextDisplay

fun main() {
    val screen = TextDisplay.named("panel")
    screen.clear()
    screen.writeAt(0, 0, "Warehouse")
}
```

`writeAt` selects `HIGH` density and rasterizes text into the same persistent RGB canvas. Its 20 × 10 cells occupy
the top-left block of a composite screen. Changing from another density clears the image first. Each write replaces its cells, including spaces. Text must fit within
one row, contain no control characters or line breaks, and use at most 256 UTF-8 bytes. `clear()` clears the entire
canvas. Existing text and graphical programs share the same writer lease.
