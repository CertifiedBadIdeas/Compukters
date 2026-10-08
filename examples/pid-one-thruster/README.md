# One-engine position hold

Guest Kotlin example for a construction with **one Creative Vector Thruster**, using the Sable and Propulsion addons.
The computer and engine must belong to the same construction. Build the whole project in the IDE, deploy it to the
computer, and run it. The first matching Creative Vector Thruster is selected.

The outer PID controls the world position of Sable's **logical rotation point**. The inner PD controller tilts the
construction toward the required acceleration and damps its angular velocity. This is a starting controller for
in-game tuning, not a guarantee of stability for an arbitrary construction.

## Construction

- Mount the engine facing up in the construction's local axes, **below the centre of mass**, close to its vertical axis.
  The moment arm is essential: an engine exactly at COM cannot correct attitude. A lateral offset creates a persistent
  torque this controller does not model. The API reports the mount relative to the computer, not relative to COM.
- Start nearly upright, with space underneath for unobstructed exhaust and room to recover the captured position.
- The engine has two steering inputs. They control pitch and roll; this example does not hold a yaw heading.
- With the program stopped, set the engine's saved thrust close to the amount needed to hover at full throttle.
  Alternatively, set `HOVER_KN` in `src/main.kt`. The estimate is `massKg * gravity / 1000` kN, before any atmosphere
  adjustment. No mass or centre-of-mass estimate is available from the current Guest snapshot.

## Target and tuning

By default, the program captures the rotation point's X/Y/Z when it starts. To hold a chosen world point, set
`HOLD_START_POINT = false` and edit `TARGET_X`, `TARGET_Y`, `TARGET_Z`. These coordinates refer to the rotation point,
not necessarily to the computer block or centre of mass.

Settings are at the top of `src/main.kt`:

| Setting | Meaning |
| --- | --- |
| `HOVER_KN` | Approximate hover thrust; `0.0` uses the engine's saved setting |
| `POSITION_P` | Acceleration toward the target |
| `POSITION_D` | Damping of the tracked point's motion |
| `POSITION_I` | Slow correction of steady error, including modest hover-thrust mismatch |
| `ATTITUDE_P` | Steering response to tilt error |
| `ATTITUDE_D` | Steering that damps angular velocity |
| `MAX_VECTOR` | Maximum nozzle control magnitude per axis; upstream accepts up to `1.0` |

First set `POSITION_I = 0.0` and tune the hover baseline and attitude damping. If the construction shakes or tilts
back and forth, lower `ATTITUDE_P`; adjust `ATTITUDE_D` until rotation settles. Then tune the position controller:
reduce `POSITION_P` if it repeatedly overshoots, and increase `POSITION_D` cautiously to damp drift. Enable a small
`POSITION_I` last. Gains depend on the engine's lever arm, construction inertia, nozzle smoothing and actual API latency.

The example assumes gravity of `9.81 m/s²`; change the value in `steer` if the world uses another setting. It uses
bounded arithmetic helpers because Guest `kotlin.math` is currently unavailable. Physics observations are asynchronous:
the loop uses elapsed server ticks instead of assuming that each iteration takes one tick. Velocity is estimated from
successive rotation-point positions, with a low-pass filter. Repeated snapshots do not advance the controller;
paused snapshots or gaps over ten ticks reset integral and velocity history. Integral is disabled for the first
100 ticks and frozen after saturated commands.

Pitch/roll commands are based on **torque**, not direct horizontal force: an upward engine below COM initially
counter-steers to tilt the construction in the desired direction. The upstream geometry is
`forceLocal ∝ (tan(30°) * vectorX, 1, tan(30°) * vectorY)` for this mounting. Thrust compensates for the vertical
projection of the commanded nozzle direction; the actual nozzle approaches its target gradually.

The program stops if tilt exceeds 60° or thrust cannot provide a usable upward component. Ctrl+C also stops it.
Closing the handle or terminating the program releases overrides and returns control to the engine's current
redstone/link inputs and saved settings; it does not latch the engine off. Stateful Propulsion handles currently
prevent this program from resuming through hibernation.

## Verification

The complete sources were compiled into a Compukter artifact using the real Guest compiler with the Sable and
Propulsion bundles. Numerical checks exercised quaternion conversion, steering signs, damping and saturation.
An idealized single-engine rigid-body simulation converged from a position offset and initial tilt at observation
intervals of 50, 150 and 250 ms, including a 5% hover-thrust mismatch. That model assumes a centred lower engine,
isotropic inertia and a 150 ms nozzle response; it does not establish stability in Minecraft or on another construction.
