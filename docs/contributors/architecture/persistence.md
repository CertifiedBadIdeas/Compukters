---
layout: default
title: Filesystem and machine lifetime
section: contributors
permalink: /ARCHITECTURE/persistence/
---

# Filesystem and machine lifetime

[← Architecture](../architecture.md)

* On this page
{:toc}

Object identity is the bits of Ref32, not a separately assigned UUID. A managed Ref32 contains its object-header
offset in the nonmoving VM heap; image/external references use their own domain payload. Identity stays stable while
an object is live, but a freed slot may be reused and another VM may produce the same bits. The heap, stack and object
identities are not persisted across machine close/recreation. Identity hashCode/default toString must not be used as
durable IDs. The persistent ComputerId below identifies a computer's filesystem, not its Guest objects.

## Persistent filesystem

The Rust runtime owns the guest filesystem and its persistence. Minecraft stores only a stable 128-bit `ComputerId`;
guest paths and bytes never enter block-entity NBT or a JVM-side mirror. Every computer sees an immutable packaged
`/rom` and a private persistent `/home`. The world store lives under `<world>/compukters/filesystems`, performs bounded
I/O on its own worker, flushes active generations on world saves, and drains, flushes, and closes before server shutdown
completes. A permanent `lock` anchor carries a process-lifetime exclusive OS file lock: a live second server is
rejected, while orderly close or process termination releases ownership without deleting the anchor. Removing a
computer through the player destruction lifecycle closes its machine before creating a
recoverable tombstone; ordinary block-entity removal during chunk unload only closes the current machine and preserves
its filesystem.

## Close barriers and persistence work

The Minecraft filesystem registry accepts asynchronous machine-close barriers. An unloading computer retains its
attachment until the barrier completes and the final generation is flushed; destruction defers its tombstone until
that point. Store shutdown starts every outstanding drain before waiting for the combined barrier. Ordinary release
does not wait for VM completion; the server-stopping hook alone permits a bounded ten-second wait. A failed close
barrier keeps the store unavailable rather than closing storage underneath a possibly live native machine.
Native flush, tombstone, recovery, and store close run on one lazy persistence executor per world, outside the
registry monitor and outside both the server tick and VM actor workers. Each world captures the configured VM actor
capacity when its store opens and admits at most that many computer identities, including pending removal/recovery;
each identity owns at most one queued or running token.
Repeated saves coalesce to the latest requested generation. The identity remains unavailable until final persistence
completes, so a quickly reloaded block retries attachment instead of opening a second machine. Failed background
carrier admission is retried once every 20 block-entity ticks; explicit terminal access may retry immediately.
Persistence failures are logged once, fail outstanding lifecycle futures, and prevent unsafe reattachment or store
close. Initial store opening happens during server startup rather than an ordinary computer tick. Calls sharing a
native world-store handle are serialized by a fair lock across VM actors and persistence work, while ordinary VM
execution stays on actor workers.

## Native store boundary

The native C ABI exposes opaque world-store lifecycle operations, machine creation inside a store, stateless
artifact verification, dedicated bounded compilation request and completion calls, and typed scalar or structured-record host-request completion, plus categorized human-readable failures.
The [native ABI reference](abi.md) owns the scalar/record wire encoding and bounds. Kotlin can select a world
store, identify a computer, request flush, tombstone, or recovery, and route compiler results, but it cannot perform
arbitrary guest file operations. Guest code reaches Rust-owned state only through declared capabilities. The guest
machine-creation calls carry a bounded, versioned schema for optional host capabilities; Rust validates and owns that
schema before admitting the executable, so addon operations use the same typed verifier contract as built-in devices.
The instruction-limited advance (introduced in C ABI 16) returns retired instruction count separately from the existing
weighted guest and maintenance budgets. The earlier advance wire result and its meaning remain unchanged.

## Tick permits

Server tick requests enter actor mailboxes with a deferred permit. After block entities and benchmark carriers submit
their requests, the NeoForge post-tick hook divides aggregate instruction capacity among the submitted runnable
computers and releases their permits. World effects remain ahead of each permit, while later actor commands stay behind
it. A deferred permit releases its worker token while waiting for the post-tick allocation.
The guest filesystem facade exposes bounded `stat`, `list`, `readText`, and `writeText`; Rust validates paths, UTF-8, permissions,
quotas, and atomic replacement while `/rom` remains immutable. The shell and editor map stable failures to user-facing
diagnostics. Executable installation remains a Rust-owned filesystem transaction and never accepts a host path.

## IDE target operations

The IDE target protocol is a separate bounded host interface rather than direct filesystem ownership. After attaching
to a target, the client may inspect supported filesystem metadata and content, upload an artifact for verification,
observe the destination revision, and deploy using a verification ticket plus the expected revision. Heartbeats and
detach bound the target attachment lifetime; revision conflicts require an explicit retry or user confirmation.

Server-side IDE verification, deployment, canonical input, and filesystem operations may suspend until the runtime
answers. The request transport admits at most 256 pending operations per server and four per player, including
suspended work. Verification retains its upload staging reservation while awaiting the runtime, and a candidate
returned after its target lease ends is closed instead of becoming a ticket. The IDE terminal also supports suspended
open, resync, input, and polling operations. Each viewer has at most one pending poll; replies are checked against the
current viewer session and machine before publication. The standalone terminal uses the same bounded asynchronous
transport as the IDE terminal, and its viewer state is discarded when the server stops.
