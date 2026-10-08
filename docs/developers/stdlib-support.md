---
layout: default
title: Guest standard library support
section: developers
permalink: /STDLIB-SUPPORT/
---

# Guest standard library support

Compukters provides native Guest Kotlin libraries, not a Kotlin/JVM classpath. This inventory describes executable APIs in the current checkout, including unreleased work. [Language support]({{ '/KOTLIN-SUPPORT/' | relative_url }}) owns syntax and lowering; the [generated API reference]({{ '/guest-api/' | relative_url }}) owns packages, signatures and KDoc.

## API groups

| Topic | Contents |
| --- | --- |
| [Core and text](stdlib/core-text.md) | Scope functions, preconditions, text search/transformation, parsing, text and hashes |
| [Mathematics](stdlib/math.md) | Portable Float/Double math, integer helpers, rounding and IEEE edge semantics |
| [Arrays and ranges](stdlib/arrays-ranges.md) | All twelve primitive arrays, reference arrays, copying and stored progressions |
| [Collections](stdlib/collections.md) | Lists, primitive specializations, selection, mutation and iterable helpers |
| [Console I/O and packages](stdlib/io.md) | Console overloads, module ownership and unavailable packages |

## Status and evidence

- [x] **Supported** — the stated API has execution evidence in the Rust VM.
- [ ] **Partial** — the named subset works, with the stated limitations.
- [ ] **Unsupported** — declarations may exist for K2, but programs cannot execute that API.

The canonical declarations live in [`guest-platform`](https://github.com/CertifiedBadIdeas/Compukters/tree/dev/modules/common/guest-platform/src/platform).
Only selected Compukters platform modules participate in Guest name resolution; host Kotlin/JVM dependencies do not.
The [Guest API reference]({{ '/guest-api/' | relative_url }}) indexes public declarations by package and symbol and
links to their source files.

## Maintenance

Update the API’s owning page together with its exact executable surface and test evidence. Update [language support]({{ '/KOTLIN-SUPPORT/' | relative_url }}) only when syntax or representation boundaries change. Keep each behavior in one inventory; link to shared nullability, equality and callback semantics. A supported API does not imply all overloads from upstream Kotlin are available.

<a id="core-and-text"></a>

[Core and text](stdlib/core-text.md)

<a id="arrays-and-ranges"></a>

[Arrays and ranges](stdlib/arrays-ranges.md)

<a id="collections-and-io"></a>

[Collections and I/O](stdlib/collections.md)

<a id="status-legend"></a>

[Status legend]({{ '/STDLIB-SUPPORT/#status-and-evidence' | relative_url }})

For agents and maintainers: follow the relative document links above, read the relevant entry and its evidence, and update that owning file in the same commit as the implementation. These index files retain the shared policy; topic pages retain the detailed contracts.
