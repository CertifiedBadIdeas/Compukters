---
layout: default
title: Programmable computers, bounded by design
description: Write Kotlin in Minecraft and run it in a deterministic managed VM.
---

# Programmable computers, bounded by design.

{: .hero-copy }
Compukters is an in-game programming platform for Minecraft. Build multi-file Kotlin projects in the integrated IDE,
use semantic editing and analysis, then deploy verified programs into a deterministic, resource-bounded managed VM.

<div class="actions">
  <a class="button primary" href="{{ '/GETTING-STARTED/' | relative_url }}">Get started</a>
  <a class="button" href="https://github.com/CertifiedBadIdeas/Compukters">View source</a>
</div>

<span class="version-chip">Minecraft 1.21.1 / 26.1.2</span>
<span class="version-chip">NeoForge</span>
<span class="version-chip">Java 21 / 25</span>

<figure class="showcase">
  <img src="{{ '/assets/images/boiler_showcase.png' | relative_url }}" alt="A Compukters text display showing live water, heat, and level readings beside a Create steam boiler">
  <figcaption>A computer monitors a Create steam boiler and writes its readings to a text display.</figcaption>
</figure>

<div class="feature-grid">
  <section class="feature-card">
    <h3>Integrated Kotlin IDE</h3>
    <p>Create persistent multi-file projects with completion, diagnostics, navigation, formatting, build, deploy, and run actions.</p>
  </section>
  <section class="feature-card">
    <h3>Deterministic runtime</h3>
    <p>Verified Compukter bytecode runs with explicit execution quotas, managed memory, and bounded host capabilities.</p>
  </section>
  <section class="feature-card">
    <h3>Real automation</h3>
    <p>Automate redstone, write to in-world displays, and connect named Create devices through passive cables on Minecraft 1.21.1.</p>
  </section>
</div>

## Choose a path

- **New player:** follow [Getting started](https://certifiedbadideas.github.io/Compukters/GETTING-STARTED/) from installation to your first running program.
- **Guest Kotlin author:** check the [Kotlin support matrix]({{ '/KOTLIN-SUPPORT/' | relative_url }}) for language features and
  the [stdlib support matrix]({{ '/STDLIB-SUPPORT/' | relative_url }}) for callable APIs. Explore signatures in the
  [Guest API reference]({{ '/guest-api/' | relative_url }}).
- **Automation builder:** learn the local-side model in [Redstone GPIO](https://certifiedbadideas.github.io/Compukters/REDSTONE/).
- **Dashboard builder:** write to an independent in-world [Text display](https://certifiedbadideas.github.io/Compukters/DISPLAY/) beside the computer or over peripheral cables.
- **Create engineer:** start with the [Create addon guide]({{ '/CREATE/' | relative_url }}) for kinetic devices, Stock Tickers, steam boilers, and peripheral cables.
- **Addon developer:** expose a typed Guest Kotlin module from an independent mod with the [Addon SDK](https://certifiedbadideas.github.io/Compukters/ADDON-DEVELOPMENT/).
- **Following development:** see the continuous [Changelog](https://certifiedbadideas.github.io/Compukters/CHANGELOG/).
- **Contributor:** start with [Architecture](https://certifiedbadideas.github.io/Compukters/ARCHITECTURE/) and [Verification](https://certifiedbadideas.github.io/Compukters/VERIFICATION/).

Compukters is under active development. The support matrix describes shipped behavior; roadmap ideas are not part of the
current compatibility contract.
