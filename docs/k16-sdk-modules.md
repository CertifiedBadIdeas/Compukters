# K16 Static SDK Modules

## Current Contract

The Normal Notebook has one server-owned SDK module bay. A module ItemStack
stores one immutable artifact identity, not filesystem bytes. The identity is
persisted with the block entity and synchronized through the notebook menu.

The current test-only identity is `sdk_fixture_v1`. It proves the reusable
mount path but is not registered as a player-obtainable production module. The
first production C SDK identity and contents belong to issue #466.

## Cold-Boot Lifecycle

Module insertion, removal, and replacement are accepted only while the runtime
is powered off. Every accepted change deletes both the current durable runtime
snapshot and its backup before the slot mutation is committed. The existing
computer id and writable storage0 volume survive.

The runtime resolves the current identity only when it creates the next native
endpoint. A successful resolution attaches one immutable K16VOL as storage1;
the VM appends hardware id `10` at `0x1000_0900`, and KraftOS mounts its ROOT/KFS
partition read-only at `/sdk`. With an empty bay, storage1 is absent and the
base hardware table is unchanged.

There is no hotplug, snapshot adaptation, missing-artifact fallback, or copy of
SDK files into storage0. Invalid or unavailable attached media fails startup
explicitly.

## Artifact Storage

Bundled artifact metadata lives in `firmware/kraftos-artifacts.properties`.
Resolved bytes are atomically materialized once per identity under:

```text
<world>/compukterkraft/artifacts/<identity>.kv
```

An existing identity must always resolve to identical bytes. The published
file is made host-read-only, every attached VM opens it read-only, and K16SNAP
stores only storage1 controller state. ItemStack NBT, chunk NBT, network menu
state, and snapshots never embed the volume.

## Verification

Run the native KraftOS mount and execution proof with:

```bash
./gradlew-sandbox-dev-parallel verifyK16SdkMount
```

Run the Minecraft block-entity and persistence lifecycle with:

```bash
./gradlew-sandbox-dev-parallel :v1_21_1-neoforge:runGameTestServer
```

`verifyLocalFull` includes `verifyK16SdkMount`. The GameTest remains a separate
headless Minecraft-server check.
