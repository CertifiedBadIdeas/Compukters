---
layout: default
title: Peripherals and cables
description: Connect computers to adjacent or named devices and understand device lifetime.
section: players
permalink: /PERIPHERALS/
---

# Peripherals and cables

A computer can reach devices directly on its six faces or through loaded Peripheral Cables. A device API belongs to
the integration that provides it: displays are built into Compukters, while Create and Propulsion devices need their
corresponding addons.

## Connect a device

For a directly adjacent device, use the API's side accessor when it offers one. Sides are relative to the computer's
front face: `front`, `back`, `left`, `right`, `top` and `bottom`.

For a named connection, connect the device to the computer with Peripheral Cables and use the Peripheral Configurator
to name the device. Give devices unique names among those reachable from the computer. Cable paths can branch and loop;
discovery stays within loaded chunks and does not force-load the world.

For example, open a named display:

```kotlin
import compukter.display.Display

fun main() {
    val screen = Display.open("panel")
    screen.writeAt(0, 0, "Connected")
    readln() // Keep the program alive while it owns the output.
}
```

An adjacent display can instead be opened with `Display.front.open()`. Follow the
[display guide]({{ '/DISPLAY/' | relative_url }}) for bounds and output ownership.

## Device handles have a lifetime

A handle refers to the device you acquired, not whichever block later occupies its position. Removing, replacing,
unloading or disconnecting a device can invalidate it. Reconnect and acquire a new handle instead of assuming an old
one will retarget. Error details and control-release behavior depend on the device API.

| Device | Guide |
| --- | --- |
| Text display | [Display]({{ '/DISPLAY/' | relative_url }}) |
| Create gauges and rotation controller | [Kinetics]({{ '/CREATE-KINETICS/' | relative_url }}) |
| Create Stock Ticker | [Logistics]({{ '/CREATE-LOGISTICS/' | relative_url }}) |
| Create steam boiler | [Boilers]({{ '/CREATE-BOILERS/' | relative_url }}) |
| Propulsion creative engines | [Propulsion]({{ '/PROPULSION/' | relative_url }}) |
