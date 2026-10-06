---
layout: default
title: Wiki
description: Find Compukters guides for players, program and addon developers, and project contributors.
permalink: /WIKI/
---

# Compukters Wiki

Choose what you want to do. The player guides cover installing and using computers; developer guides cover Guest
Kotlin and addon APIs; contributor guides cover the source checkout and its implementation.

<div class="feature-grid audience-cards">
  {% for audience in site.data.navigation %}
  <a class="feature-card" href="{{ audience.url | relative_url }}">
    <h2>{{ audience.title | escape }}</h2>
    <p>{{ audience.description | escape }}</p>
    <span class="card-action">Explore guides →</span>
  </a>
  {% endfor %}
</div>

## Common starting points

- [Install Compukters and run a program]({{ '/GETTING-STARTED/' | relative_url }}).
- [Choose an addon]({{ '/ADDONS/' | relative_url }}).
- [Look up an API]({{ '/API/' | relative_url }}).
- [Build the project locally]({{ '/DEVELOPMENT/' | relative_url }}).
- [Check what changed in a release]({{ '/CHANGELOG/' | relative_url }}).

The Wiki describes the current checkout. Use the changelog to distinguish published behavior from development features.
