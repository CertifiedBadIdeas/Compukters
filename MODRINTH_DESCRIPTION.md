# Compukters

**Compukters brings programmable computers to Minecraft, powered by Kotlin and a custom sandboxed virtual machine.**

Write programs, run them from an in-game shell, and keep their files between world sessions. Behind the scenes, Kotlin source is compiled into verified Compukter bytecode and executed by a resource-bounded Rust VM built specifically for the mod.

> **Compukters is still in early development.**
>
> The foundations are working, but the programming tools, APIs, and player-facing experience are evolving quickly. Expect incomplete features and breaking changes between development builds.

## What works today

The current development version includes:

- an in-game computer block with an interactive terminal;
- Kotlin source compilation through an isolated compiler process;
- a shell and boot system written as ordinary guest programs;
- foreground program execution;
- a persistent virtual filesystem for each computer;
- verified Compukter bytecode;
- deterministic, resource-bounded execution;
- a managed virtual machine written in Rust;
- integration with Minecraft world saves and the computer lifecycle.

There is also a standalone playground for running Kotlin projects through the same compiler and runtime used by the mod.

## Programming in Kotlin

Programs for Compukters are written in Kotlin, but they do not run on the JVM inside the virtual computer.

Instead, player code passes through the Kotlin K2 compiler frontend and is translated into Compukter's own bytecode:

```text
Kotlin source
    ↓
Kotlin K2 / IR
    ↓
Compukter bytecode
    ↓
Managed Rust VM
```

This provides a familiar statically typed language while allowing the mod to control memory, execution time, available APIs, and access to the Minecraft server.

Guest programs cannot access arbitrary Java classes, Minecraft internals, the server filesystem, or the host operating system. They interact with the outside world only through explicit capabilities provided by the computer.

## Built for automation

Compukters is intended to grow beyond a single computer block.

Future devices may range from small programmable controllers to full computers with storage, networking, peripherals, and richer runtime services. Compatible devices will share the same language and program format while differing in resources and available capabilities.

The long-term goal is to support practical automation without requiring every computer to behave like a permanently running heavyweight emulator. Programs should be able to sleep while waiting for events, operate within predictable resource limits, and scale to larger builds and multiplayer servers.

## Development status

Compukters is experimental and under active development.

The runtime, compiler, filesystem, terminal, and basic program execution are already connected end to end. The next major areas of work include expanding the guest APIs, improving filesystem and shell tooling, and building a more complete in-game programming workflow.

Currently targeting:

- **Minecraft 26.1.2**
- **NeoForge**
- **Java 25**

## Links

- [Source code](https://github.com/CertifiedBadIdeas/Compukters)
- [Development blog](https://t.me/lazyhatdev) — in Russian
- License: [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0)
