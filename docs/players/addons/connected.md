---
layout: default
title: Create Connected addon
section: players
permalink: /CONNECTED/
---

# Create Connected addon

The optional [Compukters: Connected](https://github.com/CertifiedBadIdeas/Compukters-createconnected)
addon exposes native kinetic batteries from Create: Connected 1.3.3-mc1.21.1 on Minecraft 1.21.1,
NeoForge 21.1.252 and Create 6.0.10. Install both mods on the client and server. The base mod does
not require them. Enable `"connected"` in a Guest project's addons and bind the battery through
the normal configurator network.

```kotlin
import connected.battery.KineticBattery
fun main() {
    val battery = KineticBattery.named("starter")
    println(battery.state().charge)
    battery.setDischarging(true)
    readln()
    battery.setDischarging(false)
    battery.close()
}
```

`state()` reads native tick, charge/capacity in su-ticks (72000 per su-hour), discharge mode,
actual/generated RPM, native consumed stress and whether the current program selected a mode.
A battery still needs a real charged stock and a physical kinetic connection. Native demand,
capacity and discharge RPM remain authoritative; no Guest charge setter exists.

`setDischarging(true)` selects native discharge. `false` selects charging; actual charging needs
an external kinetic source. This temporarily overrides redstone for the exact program-owned
peripheral lease. `close`, program exit, reset or loss of reachability releases the override and
restores ordinary native redstone while retaining charge. Replacement never inherits an old
handle. Control sessions cannot currently hibernate; native persisted charge survives cold boot.
