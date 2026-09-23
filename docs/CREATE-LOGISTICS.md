---
layout: default
title: Create logistics
description: Read Stock Ticker inventory and request packages from Guest Kotlin.
permalink: /CREATE-LOGISTICS/
---

# Create logistics

The optional `create` addon exposes a Create 6.0.10 Stock Ticker on Minecraft 1.21.1. Compukters and Create must both be
installed on the server. The addon is selected in a project's `compukter.toml` with `addons = ["create"]`, or by
accepting an IDE completion for `Logistics` on an attached compatible computer.

A computer can acquire a Stock Ticker on an adjacent side or by its persistent Peripheral Configurator name across
loaded Peripheral Cables. The sides are relative to the computer's front. As with [Create kinetics](https://certifiedbadideas.github.io/Compukters/CREATE-KINETICS/), a handle belongs to the exact block entity that was
acquired. Removal, replacement, unloading, or observed cable disconnection invalidates it; reconnecting does not
rebind the old handle.

```kotlin
import create.logistics.Logistics

fun main() {
    val ticker = Logistics.stockTicker("warehouse")
    val stock = ticker.snapshot()
    val index = stock.findItem("minecraft:iron_ingot")
    if (index >= 0) {
        println("Available: ${stock.count(index)}")
        println("Accepted: ${stock.request(index, 16, "workshop")}")
    }
    stock.close()
}
```

`Logistics.front.stockTicker()` and the other five side accessors use an adjacent block instead of a cable name.
`snapshot()` captures an immutable list of at most 256 entries. Each entry has a registry item ID, a display name, and
an available count. Its index identifies the **exact ItemStack variant**, including components: two entries may have
the same item ID and different names or components. The list does not change after capture. `findItem(itemId)` searches
the snapshot in one bounded host call and returns the first matching index, or `-1` if absent. To inspect another
variant with the same ID, call `findItem(itemId, previousIndex + 1)`; the start index is inclusive. IDs are exact,
case-sensitive registry IDs such as `minecraft:iron_ingot`. Searching by ID does not merge or choose among variants.
A larger stock summary fails explicitly instead of returning an incomplete list. Create represents infinite stock with
counts of at least 1,000,000,000.

To request packaging, use the index found in the snapshot:

```kotlin
val stock = ticker.snapshot()
val index = stock.findItem("minecraft:iron_ingot")
val accepted = if (index >= 0) stock.request(index, 16, "workshop") else false
stock.close()
println(accepted)
```

`request(index, quantity, address)` checks the snapshot entry and rechecks current stock for its exact variant on the
server thread. Quantity must be 1–4096 and no greater than the snapshot count. The address must be nonblank, contain no
control characters, and fit within 64 UTF-8 bytes. The return value is Create's acceptance of the package request:
`true` does **not** confirm that a package reached its destination. An out-of-stock, invalid, or stale request fails
with a Guest diagnostic; Create declining a valid request returns `false`.

One running computer can retain four stock snapshots and 64 Create device handles. Call `close()` after processing a
snapshot so long-running programs can refresh stock indefinitely. Closing a snapshot makes its later operations fail.
The captured display name is limited to 128 characters; use the item ID and snapshot index for machine decisions.
All calls use the bounded addon host path and never force chunks to load.
