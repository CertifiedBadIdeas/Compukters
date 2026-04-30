# Device / Authoring Station Domain Model

## Goal

Establish a single canonical mental model for the in-world programming-related entities in Compukter Kraft, so that current code, planned features (Laptop, Turtle, Pocket Computer), and future authoring tools all fit without conceptual collisions.

This spec is **documentation-first**. It does not change runtime behavior. Its only deliverables are written documents. All concrete refactors that follow from this model are scoped into separate phases with their own plans.

## Why

The mod currently has two in-world entity classes related to programming:

- **Computer** — a block with a VM that executes CKL programs.
- **Workbench** — a block with a native (Kotlin) IDE that helps the player write CKL programs.

The current architecture documents and codebase do not name what these two things have in common, what they don't, or how future devices fit. As a result:

- Workbench code in `modules/core` lives under `ck.core.computer.workbench.*`, signalling that Workbench is a sub-feature of Computer. In `modules/v1_21_1/v1_21_1-common`, the same kind of code lives under `ck.common.workbench.*` as a peer to computer. The two locations contradict each other.
- Several shared bridge interfaces are named `Computer*` (`ComputerControlGateway`, `ComputerInputGateway`) even though they are used by both Workbench and Computer. The names imply Computer is the primary owner.
- Laptop is described in `docs/TODOs.md` as a future feature without a clear architectural place. The current code hardcodes block-entity assumptions (`ServerComputer(level: ServerLevel)`, `TransientPairing` keyed by `BlockPos`) that would block portable runtime devices.
- The mental model "is the IDE a kind of computer?" has no canonical answer in docs, even though the answer affects every future feature decision.

This spec fixes the conceptual debt by naming the model explicitly.

## Non-goals

- No code is refactored as part of this spec.
- No new features are added.
- No public CKL API changes.
- The umbrella renames (`ComputerProfile` → `DeviceProfile`, etc.) are NOT part of this spec; they are scoped into Phase 2 below.

## Domain Model

The mod has **two orthogonal categories** of programming-related in-world entities. They are not subtypes of each other.

### Category 1: Runtime Devices

A **Runtime Device** is anything in the world that **executes** CKL programs.

A Runtime Device has, by definition:

- A VM (`BackgroundComputerVm`) running on a coroutine, executing compiled CKL bytecode.
- A `DeviceProfile` (today named `ComputerProfile`) describing CPU budget, terminal dimensions, color support, ROM contents.
- A `DeviceFamily` (today named `ComputerFamily`) identifying the API surface the device exposes to CKL programs.
- A runtime workspace — the deployed file tree the VM reads from and writes to.
- A terminal abstraction — `ScreenBuffer` plus input intake.
- Optional peripherals (modem, inventory for Turtle, fuel, etc.).

**Members today:** Computer (block).
**Planned members:** Laptop (portable item), Turtle (entity with inventory and fuel), Pocket Computer (handheld item).

What differs between members is form factor, mobility, capabilities, peripherals. The internal anatomy is the same.

A Runtime Device is **not** required to provide an in-device program editor. By design choice (see `docs/TODOs.md` item 8), program authoring is done at an Authoring Station, not on the device itself. This is the embedded-development analogy: you write firmware on a workstation, deploy to the device.

### Category 2: Authoring Stations

An **Authoring Station** is anything in the world that **helps the player write** CKL programs and is itself implemented natively (Kotlin), not in CKL.

An Authoring Station has, by definition:

- A local development workspace — the source tree the player edits, separate from any Runtime Device's workspace.
- An IDE engine — parser, type checker, autocomplete, diagnostics — sourced from the `compiler` module.
- A target descriptor — a reference to a chosen Runtime Device whose `DeviceProfile` / `DeviceFamily` the IDE adapts to.
- Sync actions — `pull`, `push`, `run`, `attach terminal` — explicit operations against the target.

An Authoring Station does **not** have a VM, does **not** execute CKL, and is **not** a Runtime Device.

**Members today:** Workbench (block).
**Possible future members:** networked Workbench (multi-target hub), Workbench upgrades, collaborative variants. They remain native.

### Bridge: Target Descriptor

The connection between the two categories is a **target descriptor** held by an Authoring Station. The descriptor identifies a specific Runtime Device and exposes its `DeviceProfile` and `DeviceFamily` so the IDE can configure capability-aware features.

In the current Workbench implementation, the target descriptor is the computer item inserted into the Workbench slot. This generalizes: any Runtime Device representable as a descriptor (item, world reference, network address) can serve as a target.

There is no shared filesystem between an Authoring Station and a Runtime Device. Movement between them is explicit, action-based, and routed through the sync actions listed above.

### Shared infrastructure (neutral, used by both categories)

These artifacts are not owned by either category. They are the substrate both categories build on. They should live in modules that depend on neither category specifically:

- **Language tooling** (`compiler` module): parser, type checker, bytecode VM, `DeviceProfile`/`DeviceFamily` data classes. Used by Authoring Stations for IDE features and by Runtime Devices for compilation and execution.
- **Workspace storage abstraction** (`core`): file CRUD over a logical filesystem. Each category instantiates it with its own root and semantics.
- **Terminal text models and font rendering** (`v1_21_1-common/ui/render`): glyph layout, color tables, fixed-width rendering. Used by Computer's terminal screen and by Workbench's terminal preview panel.
- **Input transport interfaces** (`core`): the wire-level shape of "key/mouse event delivered to a server-side state holder". Distinct from the input *interpretation*, which differs per category.

When in doubt: if the artifact would still make sense in a hypothetical mod that had only Runtime Devices, OR only Authoring Stations, it is shared infrastructure and lives outside both category packages.

## Naming Rules

- **Umbrella for Category 1: `RuntimeDevice`.** Today's code uses `Computer` as the umbrella. The model adopts `RuntimeDevice` as the canonical umbrella name. The historical `Computer` name is retained for the specific block-based variant; future variants are `Laptop`, `Turtle`, `PocketComputer`.
- **Umbrella for Category 2: `AuthoringStation`.** Today's only member is `Workbench`. Future variants reuse the umbrella.
- **Cross-category bridges use neutral prefixes.** A type used by both categories must not be named with a category-specific prefix. Specifically:
  - `ComputerControlGateway` should be `TargetControlGateway` (it controls the targeted Runtime Device from the Authoring Station's perspective).
  - `ComputerInputGateway` should be `TargetInputGateway` (it transports input events to whichever Runtime Device the consumer is currently bound to).
- **Shared infrastructure types are named for their function, not their consumer.** `WorkbenchTerminalRenderer` is a shared terminal renderer despite the name; the model recommends a function-based name (e.g., `TerminalPanelRenderer`) when the next UI DSL pass touches it.
- **In-package nesting must reflect the model.** Workbench code MUST NOT live under a `computer.*` package, and Computer code MUST NOT live under a `workbench.*` package. They are peers.

## Mapping to Current Code

This section anchors the model to today's codebase. It is not a refactor list; it is a translation table.

| Concept | Today's location |
|---|---|
| Runtime Device — the abstract thing | Implicit; no umbrella interface. Conceptually represented by `ServerComputer` plus `ComputerProfile`/`ComputerFamily`. |
| Computer (block-based Runtime Device) | `ck.common.computer.*`, `ck.core.computer.*`, `ck.impl.computer.*` |
| Authoring Station — the abstract thing | Implicit; no umbrella interface. |
| Workbench (current Authoring Station) | `ck.common.workbench.*` (peer to computer — correct), `ck.core.computer.workbench.*` (nested under computer — WRONG, see Phase 1) |
| Target descriptor | The computer item in the Workbench's target slot, plus `ComputerControlGateway` (to be renamed). |
| `DeviceProfile`/`DeviceFamily` | Currently `ComputerProfile` (in `compiler`), `ComputerFamily` (in `core`). |
| Language tooling (shared) | `compiler` module — already correctly placed. |
| Terminal rendering (shared) | `v1_21_1-common/ui/render` — correctly placed; one type misnamed (`WorkbenchTerminalRenderer`). |
| Input transport (shared) | `ck.core.computer.input.*` — function is correct, package and type names are Computer-prefixed; should become neutral. |

## Phased Rollout

This spec covers Phase 0 only. Subsequent phases each get their own brainstorming → spec → plan → implementation cycle.

### Phase 0 — Canonize the model (this spec)

**Deliverables:**
- This English spec.
- Its Russian counterpart at `docs/superpowers/specs/2026-04-30-device-authoring-domain-model-design.ru.md`.
- A "Domain Model" section near the top of `docs/ARCHITECTURE.md` that summarizes the two categories, the bridge, and the shared substrate, and links to this spec as the canonical source.

No code changes.

### Phase 1 — Audit-driven cleanup (separate plan)

**Scope:**
1. Move `modules/core/.../ck/core/computer/workbench/**` to `modules/core/.../ck/core/workbench/**`. Update all imports, including in v1_21_1 modules and tests.
2. Rename `ComputerControlGateway` → `TargetControlGateway` and update all usages.
3. Rename `ComputerInputGateway` → `TargetInputGateway` and update all usages.
4. Update `docs/ARCHITECTURE.md` package table to reflect the new layout.
5. Update `docs/TODOs.md` item 8 to reference this spec and reflect that Workbench-as-separate-entity is implemented.
6. (Optional) Rename `WorkbenchTerminalRenderer` to `TerminalPanelRenderer` if the next UI DSL pass is touching that area; otherwise defer.

**Out of scope for Phase 1:**
- Renaming `Computer` → `RuntimeDevice` anywhere.
- Renaming `ComputerProfile`/`ComputerFamily`.
- Introducing the `RuntimeDevice` interface.
- Decoupling `ServerComputer` from `BlockEntity`.

Phase 1 is low-risk: package move + targeted renames + doc edits. No semantic changes.

### Phase 2 — Runtime Device umbrella (separate plan, before Laptop)

**Scope:**
1. Introduce a `RuntimeDevice` interface in `core` describing the device-side contract: VM creation/destruction, input acceptance, screen output publication, profile/family exposure.
2. Decouple `ServerComputer` from `ServerLevel` / `BlockEntity` by introducing a level/position-aware adapter; the core `ServerComputer` (or its successor) accepts a neutral host context.
3. Generalize `TransientPairing` to support non-block-based targets (item instances, entity instances) keyed by a stable identifier rather than `BlockPos`.
4. Mechanical rename: `ComputerProfile` → `DeviceProfile`, `ComputerFamily` → `DeviceFamily`. This crosses the `compiler` module boundary; the rename must preserve the module's CKL-only stance.
5. Optional: `ComputerManager` → `DeviceManager` (or keep the `Computer` name internally if the umbrella interface alone is enough disambiguation).

**Out of scope for Phase 2:**
- Implementing Laptop, Turtle, or Pocket Computer.
- Changing CKL surface naming (the language still talks about "computer" if that is the user-facing term — orthogonal decision).

### Phase 3 — Laptop integration (separate plan, after Phase 2)

**Scope:**
- Implement Laptop as the second `RuntimeDevice` implementation.
- Define how persistent state lives on the item (NBT, server-side store keyed by item UUID, or hybrid).
- Define how the player opens the Laptop terminal from inventory.
- Define Laptop's `DeviceProfile` and `DeviceFamily` (whether it shares Advanced Computer's profile or has its own).
- Define how Workbench's target descriptor accommodates an item-based Laptop.

This phase is a feature, not a refactor; it gets a dedicated brainstorm.

## Open Questions

These are deliberately deferred to later phases. They are listed here to ensure they are not lost.

- Should the CKL user-facing terminology (in error messages, language docs, in-game tooltips) follow the internal rename to `Device`, or stay as `Computer`? Decided in Phase 2 documentation.
- Does Workbench eventually become an Authoring Station with multiple concurrent targets? If so, the target descriptor model generalizes from "single inserted item" to "selected target from a known set". Decided when the multi-target feature is brainstormed.
- Does Pocket Computer share a `DeviceFamily` with Laptop, or get its own? Decided in Phase 3+.
