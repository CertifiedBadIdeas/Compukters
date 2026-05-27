# VM Runtime API Registry Design

## Goal

Make the language import system depend on the concrete VM being targeted, not on one global built-in set for the whole project.

The design must support:

- different VM families exposing different built-in runtime modules;
- optional runtime modules enabled by host-side configuration or attached capabilities;
- typed peripheral APIs such as `monitor`, `printer`, and `modem`;
- preflight validation before program start so incompatible programs are rejected early;
- runtime inspection of whether concrete devices are currently connected.

## Scope

Included:

- VM-specific runtime module catalogs.
- Separation between base VM modules and optional VM modules.
- Typed peripheral modules backed by a host-side device registry.
- Compile-time and launch-time validation of runtime module imports.
- Runtime APIs for checking whether concrete devices are present.
- IDE behavior for compact VM-aware import completion and a separate import picker.

Excluded:

- File-to-file code imports.
- User-defined program modules.
- Hot-swapping already compiled bytecode to add new imports mid-execution.
- Detailed UI design for the import picker.
- Concrete device command surfaces for every peripheral type.

## Requirements

- Import availability must not be controlled from user code.
- Each VM type may define a different built-in runtime API surface.
- Optional runtime APIs may be added by the host/runtime layer.
- Programs must fail before execution when they import a runtime module that the current VM does not support.
- Programs must still be able to start when a typed peripheral API is available but no concrete device of that type is currently connected.
- Runtime code must be able to inspect the current device registry and wait for devices to appear.
- Import UX must avoid flooding ordinary autocomplete with a large list of modules.

## Current State

The current compiler uses one static built-in registry in [modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt](/home/lazyhat/IdeaProjects/Compukter-Kraft/modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt).

Semantic import resolution in [modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt](/home/lazyhat/IdeaProjects/Compukter-Kraft/modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt) validates imports against that static built-in set.

Runtime dispatch in [modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt](/home/lazyhat/IdeaProjects/Compukter-Kraft/modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt) separately performs capability checks and hard-coded module routing.

This means:

- the compiler sees one global module universe;
- runtime capability checks are a separate concern;
- VM-specific built-ins are not modeled directly in the import system;
- peripheral APIs such as `monitor` or `printer` do not yet have a typed runtime-module model.

## Design Overview

The language runtime module system should be modeled as a VM-owned catalog.

Each VM instance compiles and launches programs against an effective runtime module catalog assembled for that VM.

The catalog has two layers:

- `base runtime modules`
- `optional runtime modules`

`base runtime modules` are hardcoded for the VM family.

`optional runtime modules` are enabled by the host/runtime layer based on upgrades, attached capabilities, block configuration, or detected device support.

User code never edits this catalog.

Imports are validated against the effective catalog before execution begins.

Typed peripheral modules are ordinary runtime modules from the compiler's point of view, but they internally expose registry-style APIs for discovering concrete connected devices.

## Runtime API Registry Model

### `RuntimeApiRegistryProfile`

Each VM family should provide a `RuntimeApiRegistryProfile`.

Responsibilities:

- define the base runtime modules for that VM family;
- define which optional runtime modules can exist for that VM family;
- define the policy for when optional modules become enabled;
- act as the source of truth for VM-owned language imports.

This replaces the assumption that there is one universal built-in module set for every machine.

### `EffectiveModuleCatalog`

For a concrete VM instance, the runtime layer should build an `EffectiveModuleCatalog`.

It contains:

- all base modules from the VM profile;
- all currently enabled optional modules for that VM instance.

This is the catalog used by:

- semantic import resolution;
- import completion;
- import-picker UI;
- preflight compile and launch validation;
- runtime host dispatch safety checks.

### Different VM Families

This design explicitly supports different VM families exposing different built-ins.

Examples:

- a minimal VM may expose only terminal and system modules;
- a workstation-like VM may expose filesystem and process modules;
- a device-controller VM may expose redstone or monitor modules;
- another VM family may expose a different set entirely.

The important rule is that `built-in` now means `built into this VM profile`, not `built into the language globally`.

## Module Availability Semantics

The design separates two concepts:

1. runtime module availability
2. concrete device presence

### Runtime Module Availability

A runtime module is available when the current VM supports its API contract.

If `monitor` is available, then:

- `import monitor;` is legal;
- the compiler knows the `monitor` module signatures;
- runtime code may call monitor-registry functions;
- the VM runtime knows how to serve that API.

If `monitor` is unavailable, then:

- `import monitor;` is a compile error for that VM;
- launch is blocked before execution starts.

### Concrete Device Presence

Concrete device presence answers a different question: whether an actual connected device instance exists right now.

If the `monitor` module is available but no monitor is connected, then:

- compilation still succeeds;
- launch still succeeds;
- runtime registry calls such as `monitor.exists()` or `monitor.list()` report that no monitor is present;
- the program may wait for an attach event and continue later.

This distinction is required so programs can implement workflows such as waiting for a monitor or printer to be attached after the program has already started.

## Peripheral Model

### Host-side Device Registry

The VM should maintain a host-owned registry of concrete connected peripheral devices.

The registry is populated by the environment, not by user code.

Examples of sources:

- adjacent blocks;
- devices attached directly to the computer block;
- modem-based connections;
- other machines exposed as communication devices;
- future world or block devices that provide data.

The registry stores connected device instances and their metadata.

### Typed Peripheral Modules

The language should expose typed runtime modules such as:

- `monitor`
- `modem`
- `printer`

These modules do not represent one specific side or one specific attached block.

Instead, each typed module is a view over the shared device registry for one device class.

### Registry-style Typed APIs

Each typed peripheral module should expose registry-style operations appropriate to its type.

Conceptual examples:

- `exists()`
- `list()`
- `primary()`
- `findById(...)`
- `findAllByLabel(...)`

Returned handles or records should include metadata such as:

- device id;
- label;
- relative side;
- block position;
- capabilities or device-specific properties.

This avoids hard-wiring user programs to `left`, `right`, or other side literals while still making side and placement information observable when needed.

## Preflight Validation

Preflight validation should operate on runtime module availability, not on concrete device presence.

### Compile-Time Rule

When compiling a source for a given VM, import resolution runs against that VM's `EffectiveModuleCatalog`.

Outcomes:

- known available module -> success;
- known but unavailable runtime module for this VM -> compile error;
- unknown module name -> compile error.

### Launch-Time Rule

If launching a program includes compilation, the same validation naturally runs before execution begins.

If bytecode reuse or caching is added later, launch must still verify that the required runtime modules are compatible with the current VM.

### Not a Device-Presence Gate

Launch must not fail merely because a concrete device of an imported type is currently absent.

That is runtime state, not module compatibility.

## Compiler Architecture

The compiler should stop assuming one static global built-in module set.

### `ModuleCatalog`

Semantic analysis should consume a `ModuleCatalog` abstraction instead of reaching directly for a fixed singleton.

The catalog only needs to model runtime modules for this design.

Possible responsibilities:

- list modules;
- resolve module by name;
- expose documentation and function signatures;
- expose module metadata needed by IDE surfaces.

### `LanguageFrontend`

`LanguageFrontend` should accept the effective runtime module catalog for the compile target.

Import registration and semantic checks then operate against that target-specific catalog instead of one global built-in registry.

## Runtime Architecture

### Effective Catalog Construction

VM startup or compile orchestration should construct an effective runtime module catalog from:

- the VM family's `RuntimeApiRegistryProfile`;
- the current VM instance state;
- enabled optional capabilities;
- typed peripheral module enablement.

### Runtime Dispatch

Runtime dispatch should no longer depend purely on a fixed hard-coded list of modules.

Instead, dispatch should be aligned with the enabled runtime module set for the current VM.

Hard-coded typed handlers may still exist internally, but they should be registered through the effective runtime module model rather than treated as an unrelated dispatch table.

### Device Events

The runtime should expose device attach and detach events so programs can react to peripherals appearing or disappearing after start.

Examples:

- generic events such as `device_attached` and `device_detached`;
- optional typed variants such as `monitor_attached`.

## IDE Behavior

Ordinary import autocomplete should remain compact and VM-aware.

The IDE must not be permanently coupled to a live computer instance.

The long-term direction is that editing and analysis may happen away from the executing device, with the IDE receiving runtime-module context from an abstract target description rather than from direct device ownership.

This means the IDE integration should depend on an abstract runtime catalog source, not on a concrete `BackgroundComputerVm`, block entity, or open computer menu.

It should:

- show only modules from the current VM's effective runtime catalog;
- avoid showing unavailable modules;
- remain prefix-based and lightweight.

The runtime catalog source may later be backed by:

- a live computer instance;
- a computer family/profile template;
- a remote execution target;
- a detached editor session that remembers its intended target.

The full list of importable runtime modules should be available through a separate import-picker UI.

The import picker should present:

- module name;
- whether the module is base or optional;
- short documentation;
- optional explanation of why the module is available.

This keeps standard autocomplete from becoming overloaded while still making the VM-specific import space discoverable.

## IDE/Runtime Boundary

To preserve future IDE/device separation, the design should introduce a boundary such as `RuntimeModuleCatalogSource` or `ExecutionTargetDescriptor`.

Responsibilities of that boundary:

- provide the effective runtime module catalog used for analysis and import discovery;
- avoid exposing transport or device-lifecycle details to the language frontend;
- allow the same IDE code to work with either a live computer-backed target or a detached target description.

Current computer-backed workbench integration is only one adapter for this boundary, not the defining architecture.

## Error Handling

Different failure cases should remain distinct.

Examples:

- `Unknown runtime module 'x'.`
- `Runtime module 'monitor' is not supported by this VM.`
- device-absence results such as `monitor.exists() == false` or an empty list result.

Device absence should not be surfaced as an import error.

## Testing Strategy

### Compiler Tests

Add coverage proving that import validation depends on the target VM catalog.

Examples:

- a source importing a base module compiles successfully for a VM that exposes it;
- the same source fails for a VM that does not expose that module;
- importing an unavailable runtime module produces the correct diagnostic;
- importing an unknown name still produces the generic unknown-module diagnostic.

### Runtime Tests

Add coverage proving that module availability and device presence are separate.

Examples:

- a VM with `monitor` support but no connected monitor still compiles and launches programs importing `monitor`;
- the runtime registry reports zero connected monitors;
- attaching a device makes it visible to the typed module APIs;
- attach or detach events are delivered correctly.

### IDE Tests

Add coverage proving that import discovery is VM-aware.

Examples:

- compact import completion shows only the current VM's effective modules;
- unavailable modules are not offered;
- the import picker receives the full effective catalog for that VM.

## Risks

### Registry Drift Between Compiler and Runtime

If the effective catalog used by the compiler differs from the runtime-enabled module set, imports may compile but fail unexpectedly at runtime.

Mitigation:

- build both compiler and runtime dispatch from the same VM-owned catalog source;
- keep runtime guards as a second line of defense.

### Over-Generalizing Future Concerns

Trying to solve file imports or other future module systems inside this design would add unnecessary complexity.

Mitigation:

- keep this design scoped strictly to VM-owned runtime modules;
- handle file-to-file reuse as a separate future design.

### Autocomplete Noise

If every available runtime module is pushed through ordinary autocomplete, the editor may become noisy.

Mitigation:

- keep import autocomplete compact and prefix-based;
- use a separate import-picker for full discovery.

## Success Criteria

- Different VM families can expose different built-in runtime modules.
- Optional runtime modules can be enabled by the host/runtime layer without exposing import-space control to user code.
- Programs fail before execution when they import runtime modules unsupported by the current VM.
- Programs can still start when the imported typed API exists but concrete devices are not yet connected.
- Runtime code can inspect connected devices and wait for devices to appear.
- IDE import discovery stays VM-aware without overloading ordinary autocomplete.
- File imports remain out of scope and are not mixed into this runtime registry design.