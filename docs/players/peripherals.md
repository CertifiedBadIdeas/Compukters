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

For a directly adjacent device, use `Device.at(Side.front)` or the API's existing side helper. Sides are relative to the computer's
front face: `front`, `back`, `left`, `right`, `top` and `bottom`.

For a named connection, connect the device to the computer with Peripheral Cables and use the Peripheral Configurator
to name the device. Give devices unique names among those reachable from the computer. Cable paths can branch and loop;
discovery stays within loaded chunks and does not force-load the world.

For example, open a named display:

```kotlin
import compukter.display.TextDisplay

fun main() {
    val screen = TextDisplay.named("panel")
    screen.writeAt(0, 0, "Connected")
    readln() // Keep the program alive while it owns the output.
}
```

An adjacent display can instead be opened with `TextDisplay.at(Side.front)`. Follow the
[display guide]({{ '/DISPLAY/' | relative_url }}) for bounds and output ownership.

## Typed discovery

Device classes also act as providers. For example, `TextDisplay`, Create's `Speedometer`, `Stressometer`,
`RotationController`, `StockTicker` and `Boiler`, and Propulsion's `CreativeThruster` and
`CreativeVectorThruster` share these methods:

| Method | Result |
| --- | --- |
| `first()` / `first { predicate }` | First matching device; throws `NoSuchElementException` if absent |
| `firstOrNull()` / `firstOrNull { predicate }` | First matching device, or null |
| `all()` / `filter { predicate }` | List of reachable devices, optionally filtered |
| `at(Side.front)` / `atOrNull(Side.front)` | One device on that relative computer face |
| `named("name")` / `namedOrNull("name")` | One uniquely named reachable device |

```kotlin
import create.kinetics.Speedometer
import compukter.peripheral.Side

fun main() {
    val moving = Speedometer.firstOrNull { it.speed() != 0f }
    println(moving?.speed())
    for (gauge in Speedometer.filter { it.speed() > 64f }) {
        println(gauge.speed())
    }
    println(Speedometer.atOrNull(Side.top)?.speed())
}
```

Discovery follows a deterministic order by device anchor coordinates, addon id and logical device key.
`first` and `firstOrNull` stop as soon as the predicate matches. A predicate receives the typed device and may call
its operations. Snapshot resources are released after a normal return, an early match or a thrown predicate.
Optional methods return null for absence; invalid names, ambiguous names, wrong device types and resource limits
remain errors. Missing strict side/name selections throw `NoSuchElementException`.

The computer retains at most 1,024 typed handles across all integrations, with at most four open discovery snapshots
and 1,024 entries per snapshot. Discovery stays in loaded chunks. In the IDE, provider values have a dedicated color
and a **P** completion badge; the same name used as a device type keeps ordinary class presentation.
Device wrappers are value classes implementing `Peripheral`: direct device calls
use scalar handles, while nullable values, interface references and collections use typed managed wrappers.
The old `Display`, `Kinetics`, `Boilers`, `Logistics`, `Thrusters` helpers and their separate side types are removed.
Use typed providers and the common `Side` instead. Create and Propulsion use addon API line `2.0`.
Rebuild dependent Guest bundles and programs against the updated
platform; exact bundle versions and hashes still govern compiled artifacts.

## Device handles have a lifetime

A handle refers to the device you acquired, not whichever block later occupies its position. Removing, replacing,
unloading or disconnecting a device can invalidate it. Reconnect and acquire a new handle instead of assuming an old
one will retarget. An expired typed handle fails with `compukter.io.IOException`; it never revives after reconnection. Control-release behavior depends on the device API.

| Device | Guide |
| --- | --- |
| Text display | [Display]({{ '/DISPLAY/' | relative_url }}) |
| Create gauges and rotation controller | [Kinetics]({{ '/CREATE-KINETICS/' | relative_url }}) |
| Create Stock Ticker | [Logistics]({{ '/CREATE-LOGISTICS/' | relative_url }}) |
| Create steam boiler | [Boilers]({{ '/CREATE-BOILERS/' | relative_url }}) |
| Propulsion creative engines | [Propulsion]({{ '/PROPULSION/' | relative_url }}) |
