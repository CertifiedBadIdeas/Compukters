# Package Guideline

## Decision Tree: "Where do I put this new file?"

```text
Is it specific to one block/device (computer, printer, monitor)?
├── YES → Put it in that device's package
│         e.g., common/computer/block/, common/computer/menu/
│
│   What kind of file is it?
│   ├── Block / BlockEntity        → <device>/block/
│   ├── Item                       → <device>/item/
│   ├── Menu / ContainerData       → <device>/menu/
│   ├── Screen (GUI)               → <device>/screen/
│   ├── Input handler              → <device>/input/
│   ├── Network message (server)   → <device>/network/server/
│   ├── Network message (client)   → <device>/network/client/
│   ├── ServerComputer / Manager   → <device>/context/
│   ├── Loot condition             → <device>/loot/
│   └── Data model                 → <device>/data/
│
└── NO → It's shared infrastructure
    ├── Network transport / protocol → network/
    ├── UI rendering / DSL          → ui/
    ├── Coroutine infra             → infrastructure/
    ├── Platform abstraction        → platform/
    ├── Registry / binding          → binding/
    └── Utility                     → utils/
```

## Adding a New Block/Device

1. Create a new top-level package: `common/<device>/`
2. Inside it, create sub-packages as needed: `block/`, `item/`, `menu/`, `screen/`, `network/`, etc.
3. Keep shared infrastructure in the cross-cutting packages — don't duplicate it per device
4. Register the device's content in `binding/ModObjects.kt`

### Template for a new device:

```text
common/<device>/
├── block/
│   ├── <Device>Block.kt
│   └── <Device>BlockEntity.kt
├── item/
│   └── <Device>Item.kt
├── menu/
│   └── <Device>Menu.kt
├── screen/
│   └── <Device>Screen.kt
├── network/
│   ├── client/
│   │   └── <Device>ClientMessage.kt
│   └── server/
│       └── <Device>ServerMessage.kt
└── context/
    └── Server<Device>.kt (if the device has server-side state)
```

## Module Rules

### core (platform-agnostic)
- `computer/` — all computer-specific runtime logic: VM, runtime, input
- `workbench/` — authoring logic: editor state, target-aware development flows
- `gui/`, `ui/` — shared terminal/UI abstractions (no net.minecraft.* imports!)
- `platform/api/` — interfaces for loader-specific services
- `bootstrap/` — content descriptors, mod initialization contracts

### v1_x_x-common (Minecraft-facing, loader-agnostic)
- `computer/` — all computer Minecraft integration for runtime devices (blocks, items, menus, terminal UI)
- `workbench/` — Workbench Minecraft integration for authoring devices
- `network/` — shared network transport (not device-specific messages)
- `ui/` — shared rendering (FixedWidthFontRenderer, UiRenderer)
- `infrastructure/` — coroutine dispatchers, workbench gateways
- `platform/` — Minecraft input, platform-specific adapters

### v1_x_x-{loader} (loader-specific)
- `computer/` — loader-specific block entity shims (NeoForgeComputerBlockEntity)
- Root — bootstrap, registry, hooks (small files, rarely grow)

### compiler integration (planned)
- `frontend/` — pinned K2 entry points and script-source integration
- `backend/` — custom Kotlin IR lowering and Compukter artifact emission
- `artifact/` — versioned artifact models shared with the verifier boundary

VM execution remains in `host/compukter-vm`; compiler packages must not own a
second runtime implementation.

## Anti-Patterns

❌ **Don't put computer-specific code in shared packages.** ComputerTerminalClientMessage goes in `computer/network/client/`, not `network/client/`.

❌ **Don't create device-specific sub-packages in shared infrastructure.** `network/computer/` is wrong — use `computer/network/` instead.

❌ **Don't nest too deep.** Max 3 levels under the feature: `computer/network/server/KeyEventServerMessage.kt` is fine. Adding more nesting is a smell.

❌ **Don't put abstractions and implementations in different feature packages.** `AbstractComputerBlock` belongs in `computer/block/`, not in a separate `abstractions/` package.
