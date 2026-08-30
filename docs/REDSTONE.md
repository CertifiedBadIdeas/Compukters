# Redstone GPIO

Each computer exposes six redstone sides relative to its case, in the stable
order `FRONT`, `BACK`, `LEFT`, `RIGHT`, `TOP`, `BOTTOM`. Horizontal sides rotate
with the computer's facing; top and bottom remain vertical. Guest programs never
need world directions.

## Guest API

`RedstoneSignal` is a primitive-backed value class with levels `0..15` and
constants `MIN` and `MAX`. `RedstoneOutput` contains the same level plus an
optional direct-power flag; `MIN` is level zero without direct power and `MAX`
is level 15 with direct power. `RedstoneOutputs` is an immutable packed snapshot
of all six outputs, with `ALL_MIN`, `ALL_MAX`, indexed access, and `with`.

```kotlin
import compukter.redstone.Redstone
import compukter.redstone.RedstoneOutput
import compukter.redstone.RedstoneSide
import compukter.redstone.RedstoneSignal

fun main() {
    Redstone.awaitAtLeastInput(RedstoneSide.LEFT, RedstoneSignal(7))
    Redstone.setOutput(
        RedstoneSide.RIGHT,
        Redstone.output(RedstoneSignal.MAX),
    )

    val outputs = Redstone.outputs()
        .with(RedstoneSide.TOP, RedstoneOutput.MAX)
        .with(RedstoneSide.BOTTOM, RedstoneOutput.MIN)
    Redstone.setOutputs(outputs)
}
```

`input(side)` reads the current sampled level immediately.
`awaitInputChange(side)` is edge-triggered: it waits for a later sampled change
on that side. `awaitInput`, `awaitAtLeastInput`, and `awaitAtMostInput` are
level-triggered and may complete immediately from the current snapshot.

`setOutput` and `setOutputs` are blocking I/O operations. Concurrent writes in
one host batch are folded in publication order, last write wins per side, one
complete register is committed physically, and every original request completes.
At most one physical output commit occurs per computer per server tick.

## Minecraft behavior

Input changes are coalesced at the server-tick boundary. Every transmitted
packet contains the complete six-side level snapshot plus a changed-side mask.
A pulse that starts and ends between two samples may therefore be missed by
design. Waiter interest does not change world sampling.

Weak output always exposes the selected level. When `direct` is enabled, the
same level is also exposed as vanilla direct power, allowing propagation through
an adjacent solid conductor. Direct power is a flag, not a second independently
programmable level.

Minecraft owns and persists the packed output register. Program completion,
shutdown, faults, reboot, VM replacement, and chunk reload do not reset it.
Rust owns the current input snapshot, waiters, and a confirmed output mirror;
each new VM session is seeded from Minecraft before it runs.

Multi-side `awaitInputChange(vararg sides)` and a generic Minecraft-world event
transport are intentionally deferred beyond the v1 contract.
