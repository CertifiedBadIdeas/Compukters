# Compukters

<img src="modules/minecraft/shared/neoforge/src/main/resources/assets/compukters/textures/block/compukter/front.png" width="128" alt="Compukters logo">

**Programmable computers for Minecraft with an integrated Kotlin IDE and deterministic, resource-bounded execution.**

Write Kotlin projects, compile and deploy programs, automate redstone, and display output in the world. Optional
Create, Sable and Propulsion addons connect computers to machinery and construction physics on Minecraft 1.21.1.
The base mod supports Minecraft **1.21.1 / Java 21** and **26.1.2 / JDK 25** with NeoForge.

## Documentation

The [Compukters Wiki](https://certifiedbadideas.github.io/Compukters/WIKI/) is the canonical guide:

- [Players](https://certifiedbadideas.github.io/Compukters/PLAYERS/): installation, computers, devices and addons.
- [Developers](https://certifiedbadideas.github.io/Compukters/DEVELOPERS/): Guest Kotlin, API reference and addon SDK.
- [Contributors](https://certifiedbadideas.github.io/Compukters/CONTRIBUTORS/): checkout setup, architecture, tests and profiling.

Start with [Getting started](https://certifiedbadideas.github.io/Compukters/GETTING-STARTED/), or follow
[Build and run](https://certifiedbadideas.github.io/Compukters/DEVELOPMENT/) for local development. Detailed project status
and runtime behavior live in [About Compukters](https://certifiedbadideas.github.io/Compukters/ABOUT/).
The [changelog](https://certifiedbadideas.github.io/Compukters/CHANGELOG/) distinguishes published releases from
current development features.

## Repository

Minecraft-independent Kotlin modules live in `modules/common`; Minecraft code and version adapters live in
`modules/minecraft`. The pinned Rust VM is the `host/compukter-vm` submodule. Independent addons and the combined
physics development stand live under `addons/`. The Jekyll Wiki sources live in `docs/`.

Contributors should read [AGENTS.md](AGENTS.md). Architecture-scale work is tracked through
[GitHub issues](https://github.com/CertifiedBadIdeas/Compukters/issues) and the repository roadmap.

## Licensing

Original Compukters software and source material is licensed under
[Apache-2.0](LICENSE.md) unless stated otherwise. Media assets have their own
path-by-path [license inventory](MEDIA-LICENSES.md), including the CC BY 4.0
computer textures and MIT terminal fonts. Third-party material remains under
the licenses listed in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). Use of
the Compukters name and front-texture logo is described in
[TRADEMARKS.md](TRADEMARKS.md).

Copies and releases made available before the Apache-2.0 migration remain
available under the license which accompanied them; this migration does not
revoke earlier license grants.

## Links and credits

- Devlog (in Russian): https://t.me/lazyhatdev
- Source: https://github.com/CertifiedBadIdeas/Compukters
- Licenses: [software](LICENSE.md), [media](MEDIA-LICENSES.md),
  [name and logo](TRADEMARKS.md)
