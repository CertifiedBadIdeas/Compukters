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
  <a class="button" href="{{ '/WIKI/' | relative_url }}">Explore the Wiki</a>
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
    <p>Automate redstone, write displays, and connect Create, Sable and Propulsion APIs through optional Minecraft 1.21.1 addons.</p>
  </section>
</div>

## Find your guide

<div class="feature-grid audience-cards">
  {% for audience in site.data.navigation %}
  <a class="feature-card" href="{{ audience.url | relative_url }}">
    <h2>{{ audience.title | escape }}</h2>
    <p>{{ audience.description | escape }}</p>
    <span class="card-action">Explore guides →</span>
  </a>
  {% endfor %}
</div>

The [Wiki]({{ '/WIKI/' | relative_url }}) organizes installation, programming, addons and contribution guides by what
you want to do. The [addon overview]({{ '/ADDONS/' | relative_url }}) explains which integration to install, while the
[API reference]({{ '/API/' | relative_url }}) collects signatures for core and every addon.

Compukters is under active development. These docs describe the current checkout; the
[changelog]({{ '/CHANGELOG/' | relative_url }}) records published releases. Roadmap ideas are not part of the current
compatibility contract.
