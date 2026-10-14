---
layout: default
title: Orbital navigation contracts
description: Pinned Cosmonautics coordinate and transfer behavior for orbital mission development.
section: developers
---

# Orbital navigation contracts

This investigation supports [#795](https://github.com/CertifiedBadIdeas/Compukters/issues/795).
It describes the pinned **Create: Cosmonautics Modrinth version `HuCMavv8`**, archive SHA-256
`3fd9dac868400905a55164d2c9d74abc2080ae3269ca51e54e1899874945973e`.
Evidence is the archive's method bytecode, checked against a local decompilation; this is not a measured in-world
round trip. Recheck these contracts before changing the upstream dependency.

## Three distinct observations

| Observation | Meaning | Ownership |
| --- | --- | --- |
| Surface Sable pose | Construction pose in its Minecraft dimension | Individual construction |
| Deep Space Sable pose | Construction pose inside an allocated Minecraft region | Individual construction |
| `Space.orbit()` position/velocity | Central-body-frame trajectory | Deep Space instance, potentially shared by constructions |

`Space.guidance()` directions use the instance PV vectors in the pinned upstream world-axis convention.
They are directions, not a transformation for positions. Separate addon calls need matching identities and sufficiently
close game ticks; neither an orbital observation nor a direction makes a separate Sable snapshot atomic.
A transferred construction is placed at the instance center initially; subsequent local displacement must be retained
separately. Adding a Sable position to an orbital position would include the arbitrary region allocation coordinates.

## Surface to Deep Space

`SpaceTransitionHandler.init` tests construction pose Y strictly above the linked dimension's transition height,
with transfer allowed and a valid physics handle. `initInstance` samples the universe date and calls
`DeepSpaceHelper.localPositionToGlobalPositionAndRotation` with **surface position + (0, 1000, 0)** and solver velocity.

For planet radius `R`, world-border size `B`, border center `(cx, cz)`, source `(x, y, z)` and planetary rotation
`P(t)`, the helper constructs:

```text
s = 2 R / B
Q(t) = P(t) composed with the rotation taking +Y to +Z
r = Q(t) [s (x - cx), y + 1000 + R, s (z - cz)]
v = Q(t) vSurface + r cross omegaPlanet
```

The velocity is not multiplied by the border scale. The cross-product order above records upstream behavior.
If a non-null local velocity has squared length below `1e-5`, upstream substitutes `(0, 1, 0)`.
A null velocity instead produces zero orbital velocity, without the rotational term. A future pad-position observation
should therefore avoid using the non-null zero-velocity path as a definition of a stationary target's velocity.

The returned rotation is converted to JOML by `adapt(Rotation)` with components `(-q1, -q2, -q3, q0)` and used as a
premultiplied construction rotation during the warp. The construction pose position becomes the allocated instance
center, rather than the orbital vector. Do not copy quaternion components between libraries without the adapter.

## Engine motion and instance motion

`SubLevelPhysicsSystemMixin.applyVelocityToOrbit` runs after queued construction forces. It does **not** transfer every
engine-induced solver velocity immediately to the orbit. For local offset from the region center and construction
bounding-box diagonal `D`, it computes:

```text
overshoot = 2 (length(offset) + D) - instanceSideLength
```

Inside this boundary it submits zero velocity change. Beyond it, it removes a component of construction solver velocity
and submits that component to the instance: half the orthogonal component plus the outward aligned component, with an
additional outward correction when overshoot exceeds 1 m. Outside the owning instance it cancels the local linear
velocity. `DeepSpaceInstance.tick` mass-averages submitted components and adds the result to the orbital velocity.

A controller must observe orbital response over ticks and region motion; thrust divided by construction mass alone
is not a guaranteed instantaneous orbital acceleration. Multiple constructions in one region share this response.

## Deep Space to surface

`DeepSpaceInstance.tick` uses planetary rotation at the instance's local universe time to evaluate proximity.
For a sphere, distance is `max(length(r) - R, 0)`. For a cube, it is the Euclidean distance to the cube of half-size `R`:

```text
sqrt(max(abs(rx) - R, 0)^2 + max(abs(ry) - R, 0)^2 + max(abs(rz) - R, 0)^2)
```

The components here are in the rotating planet axes. The instance requests return when distance is strictly below
transition height and transfer to the linked dimension is allowed. The proximity test runs after propagation, but the
return helper receives the position/date saved **before** that propagation, with zero velocity. A projection from the
latest Guest observation is therefore an estimate, not an exact prediction of the next upstream transfer. Frame
changes within that tick also need separate validation. The shape is a server setting; `PlanetSnapshot` alone does not expose it, while
`Space.planetMapping()` supplies the configured sphere/cube setting. Subtracting radius from periapsis is not a universal clearance test for a cube.

`DeepSpaceHelper.globalPositionToLocalPositionAndRotation` selects a face by the strictly largest absolute component
of the planet-relative vector, maps that face back through border scale/center, and returns a construction rotation.
Equal largest components take a special fallback to `(0, transitionHeight, 0)` with identity rotation. This is not a
continuous geographic map at face ties. `SpaceTransitionHandler.exitDeepSpace` then overwrites destination Y with
**transitionHeight - 1000**, irrespective of the radial altitude in the observation. Each loaded construction receives
that destination plus its rotated displacement from the instance center. Thus the instance's predicted return point
alone is not the individual rocket's final surface position.

## Data needed for the mission

The current Guest APIs suffice for read-only orbital radius, speeds, elements and directions. Cosmonautics addon 1.1 additionally exposes `Space.planetMapping()` and `Space.region()` for surface mapping,
rotation-model/gravity configuration and allocated region geometry. Their use before targeting the original pad must:

- retain pad dimension, reference pose and planet/frame identity;
- obtain the target pad in the same dated central-body frame as the orbital observation;
- observe region center/bounds and construction displacement for burn authority and transfer placement;
- expose the data needed to reproduce the **actual upstream** return mapping, including construction displacement;
- report unavailable/ambiguous frames or face ties explicitly instead of manufacturing a target;
- verify the mapping for nonzero border centers, all faces, rotating planets, region offsets and frame changes.

Keep trajectory prediction, surface-coordinate conversion, manoeuvre selection and mission state in Guest Kotlin.
The addon exposes upstream observations and configuration; it does not select a target or propagate candidate orbits
for the program. Validate Guest conversion against the pinned upstream helper as a test oracle, including its face
ties and transfer placement policy.
A safe orbit needs a shape-aware clearance envelope, not just `bound == true`. Navigation tests should distinguish
mathematical adapter behavior from a real surface-to-space-to-surface experiment. Orbital insertion, revolution
counting and deorbit remain unimplemented; the successful 200 m local flight does not verify these stages.

## Guest-owned planning: telemetry audit

The first complete mission uses the existing SDK and individual engine calls. Structured request arguments,
host-side predictors and atomic engine command groups are not prerequisites. The program acquires its engines,
releases any already acquired handles if preflight fails, and sends bounded commands through the existing methods.
These calls do not promise an atomic multi-engine update; the controller must tolerate command and startup skew.

| Task | Available observations | Missing data or validation |
| --- | --- | --- |
| Compute orbital energy, angular momentum, apsides and circular speed | `OrbitSnapshot.position`, `velocity`, `gravitationalParameter`, elements and frame | None for instantaneous central-body calculations |
| Predict a coast or candidate burn in Guest | Orbital PV, mu, local universe ticks and `UNIVERSE_TICK_SECONDS` (0.05 s) | Establish the supported central-frame horizon; reject unsupported frame changes |
| Confirm a safe full orbit | Radius, transition height, transfer permissions, orbital elements and mapping sphere/cube setting | Implement a shape-aware clearance test in Guest |
| Count completed turns | Orbital position, instance/frame identity and universe ticks | Guest phase unwrapping with bounded sample gaps; no new host operation needed |
| Locate the retained pad at a future orbital time | Surface Sable pose plus mapping dimension/border and reference rotation/rate/acceleration | Implement dated Guest rotation and surface mapping; compare against upstream |
| Predict surface return and final pad miss | Orbital PV, mapping configuration and region construction position/center/bounds | Implement strict face selection, ties and rotated offset in Guest; measure actual return uncertainty |
| Turn thrust into an observed orbital change | Engine state, local/orbital velocity, region side length/diagonal and construction counts | Measure coupling/latency; counts are observations and must be monitored |
| Control attitude with multiple engines | Sable orientation/angular velocity, mass/inertia and vector-engine mounts | Mount/COM coordinate-origin reconciliation; ordinary creative thrusters currently have no mount observation |

Planet mapping/time configuration and current region context use existing result records without changing SDK
argument support. Engine/COM coordinate-origin reconciliation remains a separate geometry gap. Keep retained pad
coordinates and the navigation model in Guest state, rather than adding a host-owned mission or target registry.
Do not change existing record layouts merely to append fields: review additive operations against the addon lock
before implementing new telemetry.

Sequential requests may straddle server ticks or a transfer. Guest code must check dimension, instance, frame and
sample times, and discard observations that cannot describe the same context. Add a combined read-only observation
only if the required consistency cannot be obtained with those checks; do not assume separate calls are atomic.

Implement the first predictor as bounded central-body mathematics in Guest, tested against the pinned upstream
numerical implementation for circular, eccentric and near-radial cases. That numerical comparison does not establish
correct transfer targeting: separately compare the Guest surface mapping against the upstream helper, then measure
a real surface-to-space-to-surface round trip before autonomous deorbit.

For a passive measurement before/after a short manual burn, use
[space-telemetry](../../examples/space-telemetry/README.md). Record instance/frame/ticks and radial/transverse speed;
compare repeated observations rather than treating local speed as orbital speed.

## Additive navigation observations

`planetMapping()` reports the linked surface world border even when called from Deep Space, and retains the
rotation model's reference epoch (seconds after the upstream arbitrary epoch), adapted quaternion and raw Orekit
rate/acceleration. It does not evaluate future rotations or propagate trajectories. Surface calls use the universe
clock; space calls use the current instance-local clock. One universe tick is 0.05 seconds; the current time scale
is reported by `region()` and can change independently of game ticks. The mapping includes mu and a finite positive
central-body influence radius; other bodies can still own frames within that radius, so it is an outer bound rather
than a complete future frame-validity guarantee.

`region()` samples the logical construction origin and upstream bounding diagonal, allocated center/bounds and
physics side length. The bounding box includes the upstream extra one-block extent; the coupling formula uses
`sideLength`. Loaded and tracked-unloaded construction counts describe current sharing, not exclusive instance
ownership. The rotation model uses (-q1,-q2,-q3,q0) in Guest/JOML order; raw angular-coordinate rates require the
pinned Orekit rotation-shift convention, not an assumed Sable world-rate convention.

Evidence: the addon ABI lock preserves IDs 0..3 and appends IDs 4..5; `NavigationObservationTest` compares
nontrivial vector rotation with pinned Hipparchus/JOML and rejects invalid models. The transfer server fixture
reads the new replies from actual Guest code on the surface and in an initialized allocated space region.
That fixture checks data/transport and construction transfer; it does not establish orbit insertion or pad return.
