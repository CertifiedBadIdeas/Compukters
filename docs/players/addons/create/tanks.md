---
layout: default
title: Create fluid tanks
section: players
permalink: /CREATE-TANKS/
---

# Create fluid tanks

Create addon 2.2 exposes `create.fluids.FluidTank`. Bind and name any segment with the configurator.
All segments share the native controller, one name and one peripheral handle. Active steam boilers
retain the separate [Boiler API]({{ '/CREATE-BOILERS/' | relative_url }}).

```kotlin
import create.fluids.FluidTank
fun main() {
    val state = FluidTank.named("fuel").state()
    println("${state.amountMb}/${state.capacityMb} mB of ${state.fluid}")
}
```

The immutable state contains `gameTick`, fluid registry ID (`""` when empty), `amountMb`,
`capacityMb` and `blockCount`. Capacity is read from the native fluid capability, so compatible
platinum tanks report their actual capacity. Count a controller once when totaling stock.
Reads never fill tanks, start pumps or force chunk loads. Replacement, reshape, boiler activation,
unloading or lost reachability invalidates a retained handle; reacquire after restoring the device.
