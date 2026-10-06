---
layout: default
title: About Compukters
description: Programmable Minecraft computers with an integrated Kotlin IDE and bounded native execution.
section: players
permalink: /ABOUT/
---

# About Compukters

Compukters is an in-game programming platform built around Kotlin `.kt` projects, an integrated IDE, versioned
Compukter bytecode and a managed Rust VM. Compile and deploy programs, read redstone, write displays and use optional
Create, Sable and Propulsion addons.

These pages describe the current development checkout. Consult the [changelog]({{ '/CHANGELOG/' | relative_url }})
for the features included in a published release.

## Status

The compiler, canonical artifact, JDK 25 FFM session, managed Rust VM, standalone
playground, loader-independent `ProgramRuntimeHost`, and server computer block
form an executable vertical slice. A computer starts the source-visible no-std
`system/programs/boot.kt` from `/rom/boot`; boot launches `/rom/shell`, and the
shell can run verified extensionless programs in the foreground through
`Process.run`. The ordinary `/rom/kotlinc` program compiles one `.kt` source
from `/home` into an extensionless executable, using a server-global persistent
cache and an isolated K2 child process without blocking the server tick.
NeoForge GameTests cover registration, automatic boot, two computers compiling
and executing the same source, nested program execution, reboot, ticking,
removal, VM shutdown, and recovery of a tombstoned persistent filesystem.

The primary game target is **Minecraft 26.1.2**, built against **NeoForge 26.1.2.112**,
with **JDK 25** and the FFM runtime. The compatibility target is **Minecraft 1.21.1**,
built against **NeoForge 21.1.252**, with **Java 21** and the JNI runtime. Mod metadata
admits NeoForge starting at **26.1.2.97** and **21.1.250**, respectively; these lower
bounds are distinct from the pinned build versions. The 26.1.2 production
archive uses Minecraft's official names directly; the 1.21.1 archive is remapped
during packaging. Neither archive requires Architectury at runtime.

## Runtime boundary

`host/compukter-vm` is the pinned
[Compukter VM](https://github.com/CertifiedBadIdeas/Compukter-VM) submodule.
Kotlin compiler internals remain on the trusted JVM side; immutable verified
Compukter artifacts cross into the Rust runtime, which owns execution, quotas,
managed memory, scheduling, and host-neutral sessions.

The Rust runtime also owns each computer's filesystem. Minecraft persists only
a stable `ComputerId`; the immutable packaged `/rom` and isolated persistent
`/home` are mounted inside Rust. World data is rooted at
`<world>/compukters/filesystems`, flushed on saves and shutdown, and moved to a
recoverable tombstone when a player destroys the corresponding computer. Guest
code has bounded `FileSystem.stat`, `FileSystem.list`, `FileSystem.readText` and `FileSystem.writeText`
operations. Compilation is requested by the Rust machine from an immutable
source snapshot. The JVM service compiles or retrieves a verified artifact from
`<world>/compukters/compiler-cache`, then Rust re-verifies and atomically
installs it into the requesting computer's `/home`.

Inside a computer, compile and run a program with:

```text
kotlinc hello.kt
hello
```

Without `-o`, the output name is the source filename without `.kt`. An explicit
extensionless output is also supported:

```text
kotlinc hello.kt -o app
app
```

The current in-game compiler accepts exactly one source file per invocation.
Compiler failures leave an existing output untouched and are reported back in
the shell.

See [the current architecture]({{ '/ARCHITECTURE/' | relative_url }}).


## Licenses and source

Original software and source material use [Apache-2.0](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/LICENSE.md).
Media has a [separate license inventory](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/MEDIA-LICENSES.md);
[third-party notices](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/THIRD-PARTY-NOTICES.md) and
[name/logo usage](https://github.com/CertifiedBadIdeas/Compukters/blob/dev/TRADEMARKS.md) remain in the repository.
Earlier releases retain the licenses that accompanied them.
