---
layout: default
title: Optional addons
description: Choose Create, Sable or Propulsion integration and understand addon compatibility.
section: players
permalink: /ADDONS/
---

# Optional addons

Addons are separately installed NeoForge mods. The base Compukters mod works without them. The current first-party
integrations target **Minecraft 1.21.1**; install matching addon and upstream mods on both client and server.

| Addon | Use it for | Guide |
| --- | --- | --- |
| Compukters: Create | Kinetic devices, Stock Ticker logistics and steam boilers | [Create]({{ '/CREATE/' | relative_url }}) |
| Compukters: Sable | Construction position, orientation, linear and angular velocity | [Sable]({{ '/SABLE/' | relative_url }}) |
| Compukters: Propulsion | Creative Thruster and Creative Vector Thruster control | [Propulsion]({{ '/PROPULSION/' | relative_url }}) |

## Check compatibility

The JAR name contains both a Compukters target line and the addon's own `x.y` version. For example,
`compukters-create-1.21.1-neoforge-0.5-1.0.jar` targets the Compukters 0.5 line and is addon version 1.0.
Loader metadata enforces the exact minimum base-mod version and upstream dependencies; the filename's `0.5` does not
promise compatibility with every earlier patch. See each guide for the required upstream versions.

## Enable an API in your program

Choose only the addons your project uses in `compukter.toml`:

```toml
format = 3
name = "construction-controller"
addons = ["sable", "propulsion"]
```

The attached server advertises available APIs to the IDE and compiler. A project lock records their exact versions
and content hashes. An addon update may require resolving the lock again and rebuilding a program.

Create, Sable and Propulsion do not depend on one another's Compukters addon. Their upstream mods have their own
dependency requirements: installing Compukters: Propulsion still requires the upstream Create/Sable/Propulsion stack.

To expose your own integration, follow [addon development]({{ '/ADDON-DEVELOPMENT/' | relative_url }}). To test the
complete physics stack from source, use the [all-addon dev stand]({{ '/DEV-STAND/' | relative_url }}).
