---
layout: default
title: Redstone GPIO
section: players
permalink: /REDSTONE/
---

# Redstone GPIO

Each computer exposes six redstone sides relative to its case: `front`, `back`,
`left`, `right`, `top`, and `bottom`. Horizontal sides rotate with the
computer's facing; top and bottom remain vertical. Guest programs never need
world directions.

## Guest API

Select a side through `Redstone.<side>` and operate on that side directly:

```kotlin
import compukter.redstone.Redstone

fun main() {
    val current = Redstone.left.get()

    Redstone.right.set(15)                         // weak power by default
    Redstone.top.set(15, Redstone.Power.DIRECT)   // direct power

    val changed = Redstone.left.await()
    Redstone.front.await(7)
    Redstone.back.awaitAtLeast(7)
}
```

Levels are integers in `0..15`; passing another value fails before host I/O.
`get()` returns the latest sampled input immediately. It does not suspend the
program.

The wait operations block the current VM task:

- `await()` is edge-triggered and waits for the next sampled change. The new
  level can be any value, including `0`.
- `await(level)` is level-triggered and completes when the input equals that
  level. It may complete immediately from the current snapshot.
- `awaitAtLeast(level)` is level-triggered and completes when the input is at
  least that level. It may also complete immediately.

`await()` does not replay changes sampled before its waiter was registered. In
particular, a change may occur while the same task is suspended in a blocking
host operation such as `set()`. Use a level-triggered wait when the current
level must be observed after another blocking operation completes.

`set(level, power = Redstone.Power.WEAK)` is blocking host I/O. `WEAK` emits
ordinary weak power and is the default. `DIRECT` additionally exposes the same
level as vanilla direct power, allowing propagation through an adjacent solid
conductor. Direct power is a mode, not a second independently programmable
level.

The public API intentionally has no packed-register or multi-side builder. The
runtime may still fold concurrent writes into one physical commit per computer
per server tick; that batching is an implementation detail rather than a Guest
Kotlin data model.

## Minecraft behavior

Input changes are coalesced at the server-tick boundary. Every transmitted
packet contains the complete six-side level snapshot plus a changed-side mask.
A pulse that starts and ends between two samples may therefore be missed by
design. Waiter interest does not change world sampling.

Minecraft owns and persists the packed output register. Program completion,
shutdown, faults, reboot, VM replacement, and chunk reload do not reset it.
Rust owns the current input snapshot, waiters, and a confirmed output mirror;
each new VM session is seeded from Minecraft before it runs.

Use the side operations above for multi-side control. No public multi-side DSL is currently provided.

## Remote Redstone Port

The Redstone Port is a separate six-face peripheral. Connect it with the Peripheral Configurator and give it a
name, or place it next to a computer. No addon is required. Its sides follow the port's front face rather than the
computer's facing.

```kotlin
import compukter.redstone.Redstone
import compukter.redstone.RedstonePort

fun main() {
    val relay = RedstonePort.named("relay")
    try {
        relay.front.awaitAtLeast(7)
        relay.set(15, Redstone.Power.DIRECT, side = RedstonePort.Side.RIGHT)
        relay.front.await(0)
    } finally {
        relay.close()
    }
}
```

`front`, `back`, `left`, `right`, `top` and `bottom` expose `get()`, `await()`, `await(level)` and
`awaitAtLeast(level)`. `relay.set(15)` writes all six faces at once; the default is
`side = RedstonePort.Side.ALL`. Use `relay.set(7, side = RedstonePort.Side.TOP)` for a selected face. `RedstonePort.Side` includes `ALL`, `FRONT`, `BACK`, `LEFT`, `RIGHT`, `TOP`, `BOTTOM`;
it selects outputs and is separate from `compukter.peripheral.Side`, which selects an adjacent device.
Levels and power modes match the computer API. Port operations, including `get()`, suspend
for host I/O. Inputs are sampled each server tick; edge waits do not replay earlier changes. The port does not
passively conduct power between its faces.

Acquisition is exclusive to one running program, as for all typed peripherals. Closing the final handle, program
exit, loss of connectivity, removal or unload clears all outputs. Port output levels and pending waits are
transient: they are not restored from saves. After hibernation a retained port handle is stale and a waiting
operation reports `compukter.io.IOException`; reacquire the port explicitly. This lifetime applies to the remote
port; the computer's own GPIO retains its existing persisted output register.
