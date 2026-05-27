# VM-Owned Workbench Session Design

## Summary

Move the Workbench document/session authority behind the running VM instead of letting the
Workbench talk directly to the server-side workspace storage.

The current Workbench CRDT session is useful for client-side editing and multiplayer conflict
handling, but its backend still behaves like a side channel into the computer filesystem. That
does not match the Rust-owned VM direction: the VM should be the owner of its filesystem and
the Workbench should interact with it through a service running inside the VM.

This document captures the target architecture only. It does not implement the migration.

## Goals

- Treat the running VM as the authority for Workbench filesystem operations.
- Avoid direct Workbench access to slot/workspace filesystem state.
- Keep the current CRDT editor session until a VM-backed replacement exists.
- Preserve multiplayer editing semantics where possible.
- Make offline/reboot/error states explicit instead of silently using a storage fallback.
- Leave the Rust VM/runtime cleanup unblocked by not changing Workbench behavior in this slice.

## Non-Goals

- Do not remove the existing Workbench CRDT implementation in this design-only slice.
- Do not remove current Minecraft network messages yet.
- Do not redesign the editor UI.
- Do not bypass the VM by adding a new privileged Kotlin filesystem API.
- Do not require Workbench to mount or inspect item slots directly.

## Target Architecture

The target flow is:

```text
Client Workbench UI
  -> Minecraft network
  -> server computer runtime
  -> running VM service
  -> VM filesystem APIs
```

The Workbench no longer owns filesystem reads or writes. It sends document/session requests to
the server runtime. The server runtime forwards them to a VM-resident Workbench service. That
service performs normal filesystem operations from inside the VM and sends responses back through
the runtime.

The service can be implemented as a small system daemon, tentatively `workbenchd`, launched by
firmware during boot. It should use the same filesystem APIs that user programs use, so Workbench
observes the same state and permissions as the running computer.

## Session Model

Opening a file creates a Workbench session against the VM service.

The VM service returns a document snapshot:

- path;
- text;
- version or revision token;
- optional metadata used by the IDE layer.

The client editor may keep using the local CRDT replica for editing and cursor stability. The
important change is where the authoritative load/save/session operations terminate: they should
terminate at the VM service, not at direct workspace storage.

For the transition phase, the existing CRDT server can remain as the multiplayer merge layer, but
its persistence backend should become the VM service.

## VM Service API

The first VM-owned Workbench service needs a small request/response protocol:

- `List(path) -> entries | error`
- `Open(path) -> documentSnapshot | error`
- `ApplyOps(path, baseRevision, ops) -> ack/snapshot | error`
- `SaveText(path, baseRevision, text) -> revision | error`
- `Close(path) -> ok`
- `SendCursor(path, cursor) -> ok`

`ApplyOps` keeps the current CRDT route viable. `SaveText` is useful as a simpler compatibility
bridge and as an escape hatch for formatter/cleanup actions that operate on whole text.

The protocol should use explicit request IDs, explicit error codes, and bounded payload sizes.
Large documents can stay unsupported initially if the current Workbench already has practical UI
limits.

## Runtime Bridge

The Kotlin server runtime should expose a narrow bridge between Minecraft networking and the
running VM service.

Responsibilities:

- route Workbench requests to the correct computer VM;
- fail fast if the VM is not running or the service is unavailable;
- enforce request timeouts;
- translate VM service responses into existing or replacement Workbench network messages;
- avoid reading or writing filesystem state directly.

This bridge is not a new filesystem owner. It is only a transport between the Workbench UI and the
VM-owned service.

## Offline And Reboot Behavior

If the VM is stopped, booting, rebooting, or the Workbench service is not ready, the Workbench
should show an explicit unavailable state.

Expected behavior:

- opening a document fails with a clear "VM unavailable" state;
- already-open sessions become stale during reboot;
- pending edits are either held client-side with a visible stale indicator or rejected by the
  session contract;
- after reboot, the client must reopen the session and receive a fresh snapshot.

There should be no fallback to direct slot/workspace filesystem access.

## Migration Path

1. Keep the existing CRDT Workbench code unchanged while the VM service contract is designed.
2. Add VM service request/response transport behind feature tests.
3. Route Workbench list/open/save through the VM service while keeping CRDT locally.
4. Move CRDT session persistence from direct workspace storage to the VM service.
5. Remove legacy direct workspace/session fallbacks after VM-backed sessions are the only
   production path.

## Testing Strategy

Future implementation should add tests for:

- Workbench fails clearly when the VM service is unavailable.
- List/open/save requests are routed through the VM service, not direct workspace storage.
- Reboot invalidates active sessions and requires reopening.
- CRDT ops persist through the VM service backend.
- Direct workspace fallback paths are not used in production Workbench wiring.

## Acceptance Criteria

- The design is documented without changing current Workbench behavior.
- Future implementation has a clear target that keeps filesystem authority inside the running VM.
- Cleanup of Workbench fallbacks is deferred until a VM-owned replacement exists.
