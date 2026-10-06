---
layout: default
title: Programming guide
description: Build Guest Kotlin projects, run programs, and use bounded cooperative waits.
section: developers
permalink: /PROGRAMMING/
---

# Programming guide

If this is your first computer, follow [Getting started]({{ '/GETTING-STARTED/' | relative_url }}) first. This guide
connects the project workflow to the language and APIs available inside the VM.

## Choose an authoring workflow

The client IDE stores projects under `<game directory>/compukters/ide/projects`. It supports multiple Kotlin files,
semantic diagnostics, completion, navigation, formatting, build and deployment. The Run action saves, builds, deploys
the project's program and submits it to the attached computer's shell.

Inside the computer, `edit hello.kt` opens the terminal editor. `kotlinc hello.kt` compiles one source into the
extensionless `/home/hello` executable; `hello` runs it. Use `kotlinc hello.kt -o app` for a different output name.
The in-computer compiler accepts one source file per invocation; use the IDE for a multi-file project.

## Check the supported Kotlin subset

Compukters does not execute JVM bytecode or arbitrary Kotlin/JVM libraries. Consult
[Kotlin support]({{ '/KOTLIN-SUPPORT/' | relative_url }}) for language semantics and
[stdlib support]({{ '/STDLIB-SUPPORT/' | relative_url }}) for available library functions. The
[API reference]({{ '/API/' | relative_url }}) lists core and addon declarations.

The current platform includes console/terminal I/O, bounded filesystem operations, foreground processes, cooperative
tasks and integer channels, redstone, sound and displays. Optional APIs are selected through your project's addon list.

## Wait cooperatively

Use a device wait when waiting for an event, or `Tasks.sleepTicks` for a periodic loop. This suspends the task instead
of repeatedly consuming its instruction allowance:

```kotlin
import compukter.concurrent.Tasks

fun main() {
    var count = 0
    while (true) {
        println(count)
        count += 1
        Tasks.sleepTicks(20)
    }
}
```

Timers use server ticks; ordinary coroutines and host threads are not part of the Guest platform. CPU work, memory and
host operations remain bounded even when a program launches multiple cooperative tasks.

## Diagnose failures

Read compiler diagnostics before deployment and inspect the target terminal for runtime failures and bounded source
stacks. Use **Ctrl+T** in the terminal or IDE target terminal to stop the foreground command and its nested work and
return to the shell. Release device control before exiting when a device API exposes `close()`.

Files in `/home` survive ordinary world saves and reloads. Program memory and tasks currently do not survive VM
recreation: transparent execution hibernation is under design. Treat persistence of files and continuation of execution
as separate guarantees.
