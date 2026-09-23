---
layout: default
title: Create boilers
description: Monitor Create steam boiler water, heat, and level from Guest Kotlin.
permalink: /CREATE-BOILERS/
---

# Create boilers

The optional `create` addon monitors steam boilers on Minecraft 1.21.1 with Create 6.0.10. A Fluid Tank structure becomes
a boiler when a Steam Engine or Steam Whistle is attached. Connect a Peripheral Cable to any loaded tank block, then
give that block a unique name with the Peripheral Configurator. An adjacent tank block can also be addressed by side.
The reading comes from the structure's controller, so every segment of the same boiler reports the same values.

Enable the addon with `addons = ["create"]` in `compukter.toml`, or accept a `Boilers` completion in the attached IDE.

```kotlin
import create.boiler.Boilers

fun main() {
    val boiler = Boilers.boiler("main_boiler")
    println("Water: ${boiler.waterSupply()} mB/t, level ${boiler.waterLevel()}")
    println("Heat: ${boiler.heatLevel()}, boiler level ${boiler.level()}")
    println("Passive heating: ${boiler.isPassive()}")
}
```

`Boilers.front.boiler()` and the other five side accessors work on the adjacent block. Only an active boiler can be
acquired; an ordinary Fluid Tank is unavailable. A handle is tied to the exact selected tank block and its current
controller. Removing or replacing either block, changing the controller or tank size, unloading it, or breaking the
named cable path invalidates the handle. Reconnecting requires acquiring a new handle.

| Method | Meaning |
| --- | --- |
| `waterSupply(): Float` | Create's sampled incoming water rate in millibuckets per tick; this is not stored water. |
| `waterLevel(): Int` | Water contribution to the boiler level, clamped to 0–18. Create derives it from the sampled supply: 10 mB/t per level, rounding supply upward before integer division. |
| `heatLevel(): Int` | Create's heat gauge before water and size limits. Passive heat returns one, as shown by Engineer's Goggles. |
| `level(): Int` | Effective active level: the minimum of active heat, water level, and the tank-size limit, clamped by Create to 0–18. |
| `isPassive(): Boolean` | Whether Create reports passive heating when the boiler has enough size and water. Passive operation has no numerical active level, so `level()` remains zero. |

These are live server readings. Separate calls can observe different ticks; the API does not freeze a snapshot. The
boiler consumes incoming water as supply instead of storing a visible water amount. The integration only reads Create
state; water pipes, pumps, and heat sources remain controlled by their own machinery.
